"""Hangprofil a böngészőben felvett mondatokból (POST /profiles).

Ugyanaz a menet, mint a CLI-prototípusban (hangprofil.py: enroll), csak a mondatok nem
mikrofonról, hanem fájlból jönnek (WebM/Ogg/MP4/WAV — a PyAV dekódolja), és újrakérdezés
helyett a rossz mondat kimarad, figyelmeztetéssel:
  1. háttérzaj a rövid csend-felvételből (ha van),
  2. mondatonként VAD (csak a beszéd marad) + minőségellenőrzés,
  3. összhang: minden mondat ugyanattól a beszélőtől-e (közbeszólás, zaj kiszűrése),
  4. hanglenyomat a kiszűrt beszédből, és a beszéd mentése WAV-ként (újraszámoláshoz).
"""
import logging

import numpy as np
from faster_whisper.audio import decode_audio
from silero_vad import load_silero_vad

from profil_core import (EMBED_MODEL, MIN_SENTENCE_SPEECH, MIN_TOTAL_SPEECH, SR, SpeakerID, cosine,
                         save_wav, sentence_problems, speech_only)

log = logging.getLogger("voice.enrollment")

# Ennyi mondatnak kell átmennie a minőségellenőrzésen (a prototípus 8-at olvastat fel)
MIN_GOOD_SENTENCES = 3


class EnrollmentError(Exception):
    """A felvétel nem használható profilnak — az üzenet az orvosnak szól."""


_vad = None
_speaker_id: SpeakerID | None = None


def _models() -> tuple:
    # Kicsi modellek (VAD: CPU, hanglenyomat: ~25 MB) — egyszer töltjük be, a szolgáltatás futása alatt
    global _vad, _speaker_id
    if _vad is None:
        _vad = load_silero_vad()
    if _speaker_id is None:
        _speaker_id = SpeakerID()
    return _vad, _speaker_id


def decode(path: str) -> np.ndarray:
    """Bármilyen (a PyAV által ismert) hangfájl → 16 kHz mono float32."""
    try:
        return decode_audio(path, sampling_rate=SR).astype(np.float32)
    except Exception as e:  # noqa: BLE001 — sérült / üres feltöltés
        raise EnrollmentError(f"A hangfájl nem olvasható ({type(e).__name__})") from e


def enroll(sentence_paths: list[str], noise_path: str | None, output_wav: str) -> dict:
    vad, spk = _models()
    warnings: list[str] = []

    noise_rms = 0.0
    if noise_path:
        noise = decode(noise_path)
        noise_rms = float(np.sqrt(np.mean(noise ** 2))) if len(noise) else 0.0
        if noise_rms > 0.02:
            warnings.append(f"Viszonylag zajos környezet (zajszint: {noise_rms:.3f}) — csendesebb helyen pontosabb")

    segments: list[np.ndarray] = []
    for idx, path in enumerate(sentence_paths, 1):
        raw = decode(path)
        speech = speech_only(raw, vad)
        errors, warns = sentence_problems(raw, speech, noise_rms)
        warnings.extend(f"{idx}. mondat: {w}" for w in warns)
        if errors:
            warnings.append(f"{idx}. mondat kihagyva: " + "; ".join(errors))
            continue
        segments.append(speech)

    if len(segments) < MIN_GOOD_SENTENCES:
        raise EnrollmentError(
            f"Csak {len(segments)} mondat volt használható (legalább {MIN_GOOD_SENTENCES} kell). "
            "Ellenőrizd a mikrofont (kiválasztott eszköz, némítás, szint), és vedd fel újra.")

    # Összhang-ellenőrzés: a többitől eltérő hangú mondat (zaj, közbeszólás) kimarad
    embs = [spk.embed(s) for s in segments]
    sims = []
    for i, e in enumerate(embs):
        others = np.mean([x for j, x in enumerate(embs) if j != i], axis=0)
        sims.append(cosine(e, others))
    median = float(np.median(sims))
    keep = [i for i, s in enumerate(sims) if s >= median - 0.15]
    dropped = len(segments) - len(keep)
    if dropped:
        warnings.append(f"{dropped} mondat hangja eltért a többitől (zaj vagy közbeszólás?), kihagyva")
    segments = [segments[i] for i in keep]

    audio = np.concatenate(segments)
    net = len(audio) / SR
    if net < MIN_SENTENCE_SPEECH * 2:
        raise EnrollmentError("Nem gyűlt össze elég beszéd a profilhoz. Ellenőrizd a mikrofont, és vedd fel újra.")
    if net < MIN_TOTAL_SPEECH:
        warnings.append(f"Csak {net:.0f} mp használható beszéd gyűlt össze (ajánlott: {MIN_TOTAL_SPEECH:.0f}+); "
                        "a profil működik, de pontatlanabb lehet")

    emb = spk.embed(audio)
    if emb.shape != (256,) or not np.all(np.isfinite(emb)):
        raise EnrollmentError(f"Váratlan hanglenyomat ({emb.shape})")
    save_wav(output_wav, audio)
    log.info("Hangprofil kész: %.1f mp beszéd, %d mondat, %d figyelmeztetés", net, len(segments), len(warnings))
    return {
        "status": "READY",
        "embedding": [float(x) for x in emb],
        "embeddingModel": EMBED_MODEL,
        "netSpeechSec": round(net, 2),
        "sentencesUsed": len(segments),
        "warnings": [w[:500] for w in warnings][:20],
    }
