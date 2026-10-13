"""Felvétel feldolgozása (POST /recordings): beszédfelismerés, beszélő-szétválasztás, szerepkiosztás.

A prototípus (beszed_pipeline.py) lépései, két eltéréssel:
  - a hang a böngésző felvétele (WebM/Ogg/MP4), memóriában dekódolva megy a modellekhez
    (így a pyannote-nak nem kell fájlt olvasnia — Windows-on a torchcodec ffmpeg nélkül elhasal);
  - az orvost nem egy hangmintafájl, hanem a Springtől kapott hanglenyomat azonosítja.
8 GB VRAM: a Whisper és a diarizáció modelljét a lépés után felszabadítjuk, hogy a MedGemma-
átnézés (review.py, Ollama) elférjen.
"""
import gc
import logging
import time

import numpy as np
import torch
from faster_whisper import WhisperModel
from pyannote.audio import Pipeline
from pyannote.core import Segment

import beszed_pipeline as bp
import enrollment
import local_models
from profil_core import SR, cosine

log = logging.getLogger("voice.transcription")

ROLE_TO_SPEAKER = {"orvos": "DOCTOR", "beteg": "PATIENT", "egyéb": "OTHER"}
# A hangprofil-küszöb még nincs behangolva (ld. voice-module-handoff): ez alatt csak figyelmeztetünk,
# a szerepkiosztás a legjobban hasonlító beszélőt akkor is orvosnak veszi.
DOCTOR_SIMILARITY_WARN = 0.3
# Ennél hasonlóbb „másik” beszélő az orvos hangja (a diarizáció bontotta szét)
DOCTOR_SAME_VOICE = 0.75
MIN_SEGMENT_SEC = 1.0
MAX_SEGMENTS_PER_SPEAKER = 5
# Megszólalásonkénti hanglenyomathoz legalább ennyi beszéd kell (rövidebbnél a beszélő átlaga marad)
MIN_TURN_EMBED_SEC = 1.5
# Ez alatti Whisper szó-valószínűség = bizonytalan szó: a MedGemma ezekről kap kérdést (review.py)
UNCERTAIN_WORD_PROB = 0.5


class TranscriptionError(Exception):
    """A felvétel nem dolgozható fel — az üzenet az orvosnak szól."""


def _free_gpu() -> None:
    gc.collect()
    if torch.cuda.is_available():
        torch.cuda.empty_cache()


def transcribe(audio: np.ndarray, mode: str) -> list[dict]:
    compute_type = "float16" if bp.DEVICE == "cuda" else "int8"
    try:
        path = local_models.asr_model(bp.ASR_MODEL)
    except local_models.ModelMissingError as e:
        raise TranscriptionError(str(e)) from e
    model = WhisperModel(path, device=bp.DEVICE, compute_type=compute_type)
    try:
        segments, _ = model.transcribe(
            audio,
            language="hu",
            word_timestamps=True,
            vad_filter=True,
            beam_size=5,
            initial_prompt=bp.MODES[mode]["initial_prompt"],
        )
        words = []
        for seg in segments:  # generátor: itt fut a tényleges felismerés
            for w in seg.words or []:
                words.append({"start": w.start, "end": w.end, "text": w.word, "prob": w.probability})
        return words
    finally:
        del model
        _free_gpu()


def diarize(audio: np.ndarray, mode: str) -> list[tuple[float, float, str]]:
    try:
        pipe = Pipeline.from_pretrained(local_models.diarization_model())
    except local_models.ModelMissingError as e:
        raise TranscriptionError(str(e)) from e
    except Exception as e:  # noqa: BLE001 — sérült / hiányos helyi modell
        raise TranscriptionError(f"A diarizációs modell nem tölthető be: {type(e).__name__}: {e}"[:500]) from e
    if pipe is None:
        raise TranscriptionError("A diarizációs modell nem tölthető be (HF_TOKEN, a modell feltételei elfogadva?)")
    try:
        pipe.to(torch.device(bp.DEVICE))
        kwargs = {}
        if bp.MODES[mode]["num_speakers"]:
            kwargs["num_speakers"] = bp.MODES[mode]["num_speakers"]
        out = pipe({"waveform": torch.from_numpy(audio)[None, :], "sample_rate": SR}, **kwargs)
        return [(t.start, t.end, spk) for t, spk in out.exclusive_speaker_diarization]
    finally:
        del pipe
        _free_gpu()


def words_to_turns(words: list[dict], diar: list[tuple]) -> list[dict]:
    """A prototípus words_to_turns-je, de a szavakat (és a Whisper-valószínűségüket) is megtartja."""
    turns = []
    for w in words:
        mid = (w["start"] + w["end"]) / 2
        spk = next((s for a, b, s in diar if a <= mid <= b), None)
        if spk is None:
            spk = (min(diar, key=lambda d: min(abs(mid - d[0]), abs(mid - d[1])))[2]
                   if diar else "SPEAKER_00")
        if turns and turns[-1]["speaker"] == spk:
            turns[-1]["words"].append(w)
            turns[-1]["end"] = w["end"]
        else:
            turns.append({"speaker": spk, "start": w["start"], "end": w["end"], "words": [w]})
    for t in turns:
        t["text"] = "".join(w["text"] for w in t["words"]).strip()
    return turns


def uncertain_spans(words: list[dict]) -> list[str]:
    """Az egymást követő bizonytalan szavak csoportjai, ahogy a szövegben állnak."""
    spans, current = [], []
    for w in words:
        if w.get("prob") is not None and w["prob"] < UNCERTAIN_WORD_PROB:
            current.append(w["text"])
        elif current:
            spans.append("".join(current).strip())
            current = []
    if current:
        spans.append("".join(current).strip())
    return [s for s in spans if s]


def _crop_embedding(spk_model, file: dict, start: float, end: float) -> np.ndarray:
    """Hanglenyomat egy szakaszból. A szakasz vége a hang végére eshet (a diarizáció / az Opus-dekódolás
    kerekítése miatt akár picit túl is): a pyannote ezt hibának veszi, ezért a hosszra vágjuk."""
    duration = file["waveform"].shape[-1] / SR
    end = min(end, duration - 0.01)
    return np.ravel(spk_model.inference.crop(file, Segment(max(0.0, start), end)))


def assign_roles(audio: np.ndarray, diar: list[tuple], doctor_emb: np.ndarray,
                 warnings: list[str]) -> tuple[dict, dict]:
    """(beszélő → szerep, beszélő → hasonlóság az orvos hanglenyomatához)."""
    speakers = sorted({s for _, _, s in diar})
    _, spk_model = enrollment._models()
    file = {"waveform": torch.from_numpy(audio)[None, :], "sample_rate": SR}
    scores: dict[str, float] = {}
    for spk in speakers:
        segs = sorted([(a, b) for a, b, s in diar if s == spk and b - a > MIN_SEGMENT_SEC],
                      key=lambda x: x[1] - x[0], reverse=True)[:MAX_SEGMENTS_PER_SPEAKER]
        if not segs:
            continue
        embs = [_crop_embedding(spk_model, file, a, b) for a, b in segs]
        scores[spk] = cosine(np.mean(embs, axis=0), doctor_emb)
    log.info("Hasonlóság az orvos hanglenyomatához: %s", {k: round(v, 3) for k, v in scores.items()})

    if not speakers:
        return {}, {}
    if scores:
        doctor = max(scores, key=scores.get)
        if scores[doctor] < DOCTOR_SIMILARITY_WARN:
            warnings.append(f"Az orvos hangja nem azonosítható biztosan (legjobb hasonlóság: {scores[doctor]:.2f}) — "
                            "ellenőrizd a beszélők hozzárendelését")
    else:
        # minden szakasz rövid: nincs mihez hasonlítani — a prototípus heurisztikája (több megszólalás)
        counts = {s: sum(1 for *_, x in diar if x == s) for s in speakers}
        doctor = max(counts, key=counts.get)
        warnings.append("Túl rövid megszólalások: az orvos a hangprofil helyett a megszólalások száma alapján lett kijelölve")

    # Az sbo mód fix 2 beszélőt kér a diarizációtól: ha valójában csak az orvos beszél (vagy a hangját két
    # klaszterre bontja), a „másik” beszélő is az orvos hangja. Mért: egybeszélős felvételen mindkét klaszter
    # 0,96+ hasonlóságú volt; két különböző ember jellemzően 0,3 alatt.
    same_voice = [s for s in speakers if s != doctor and scores.get(s, -1.0) >= DOCTOR_SAME_VOICE]
    if same_voice:
        warnings.append(f"A diarizáció {len(same_voice) + 1} beszélőre bontotta az orvos hangját (hasonlóság ≥ "
                        f"{DOCTOR_SAME_VOICE}); ezek a megszólalások az orvoséi")
    others = [s for s in speakers if s != doctor and s not in same_voice]
    roles = {doctor: "orvos", **{s: "orvos" for s in same_voice}}
    if others:
        # a legtöbbet beszélő nem-orvos a beteg, a többi hozzátartozó / harmadik fél
        talk = {s: sum(b - a for a, b, x in diar if x == s) for s in others}
        patient = max(talk, key=talk.get)
        for s in others:
            roles[s] = "beteg" if s == patient else "egyéb"
    return roles, scores


def turn_similarities(audio: np.ndarray, turns: list[dict], doctor_emb: np.ndarray) -> dict:
    """Megszólalásonkénti hasonlóság az orvos hanglenyomatához (a diarizáció szakaszhatárainál
    egy-egy megszólalás rossz beszélőhöz kerülhet — ez jelzi a review-nak)."""
    _, spk_model = enrollment._models()
    file = {"waveform": torch.from_numpy(audio)[None, :], "sample_rate": SR}
    out = {}
    for t in turns:
        if t["end"] - t["start"] < MIN_TURN_EMBED_SEC:
            continue
        try:
            emb = _crop_embedding(spk_model, file, t["start"], t["end"])
            out[id(t)] = cosine(emb, doctor_emb)
        except Exception:  # noqa: BLE001 — a hangfelvétel szélén a crop elcsúszhat; marad a beszélő átlaga
            continue
    return out


def process(audio_path: str, mode: str, doctor_embedding: list[float]) -> dict:
    """A felvétel → megszólalások (chat). A MedGemma-átnézést a hívó (worker) futtatja utána."""
    if mode not in bp.MODES:
        raise TranscriptionError(f"Ismeretlen mód: {mode}")
    doctor_emb = np.asarray(doctor_embedding, dtype=np.float32)
    if doctor_emb.shape != (256,):
        raise TranscriptionError("Az orvos hanglenyomata hibás (256 dimenzió kell)")
    try:
        audio = enrollment.decode(audio_path)
    except enrollment.EnrollmentError as e:
        raise TranscriptionError(str(e)) from e
    if len(audio) < SR:
        raise TranscriptionError("A felvétel rövidebb 1 másodpercnél")
    warnings: list[str] = []

    t0 = time.perf_counter()
    words = transcribe(audio, mode)
    if not words:
        raise TranscriptionError("A felvételben nem volt felismerhető beszéd")
    t1 = time.perf_counter()
    diar = diarize(audio, mode)
    t2 = time.perf_counter()
    turns = words_to_turns(words, diar)
    roles, scores = assign_roles(audio, diar, doctor_emb, warnings)
    turn_sims = turn_similarities(audio, turns, doctor_emb)
    t3 = time.perf_counter()
    log.info("ASR %.1f s, diarizáció %.1f s, szerepkiosztás %.1f s; %d szó, %d megszólalás",
             t1 - t0, t2 - t1, t3 - t2, len(words), len(turns))

    chat = []
    for t in turns:
        text = t["text"].strip()
        if not text:
            continue
        role = roles.get(t["speaker"], "egyéb")
        sim = turn_sims.get(id(t), scores.get(t["speaker"]))
        chat.append({
            "id": len(chat),
            "role": role,
            "speaker": ROLE_TO_SPEAKER[role],
            "similarity": None if sim is None else round(max(-1.0, min(1.0, sim)), 3),
            "start": t["start"],
            "end": t["end"],
            "text": text,
            "uncertain": uncertain_spans(t["words"]),
            # a beszélő (klaszter) átlagos hasonlósága — a review ehhez méri a megszólalásét
            "speaker_similarity": scores.get(t["speaker"]),
        })
    if not chat:
        raise TranscriptionError("A felvételben nem volt felismerhető beszéd")
    return {"chat": chat, "warnings": warnings, "durationSec": len(audio) / SR}
