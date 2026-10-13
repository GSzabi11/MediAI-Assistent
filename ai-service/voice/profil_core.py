"""
Hangprofil – a felvételtől független rész (VAD-szűrés, minőségellenőrzés, hanglenyomat).

A CLI-prototípus (hangprofil.py, mikrofonnal) és a hangszolgáltatás (service.py, a böngészőben
felvett mondatokkal) is ezt használja, így a profil mindkét úton ugyanúgy készül.
"""
import os
import wave

import numpy as np
import torch
from silero_vad import get_speech_timestamps

SR = 16000
CHUNK = 512  # a Silero VAD 16 kHz-en 512 mintás blokkokat vár
EMBED_MODEL = "pyannote/wespeaker-voxceleb-resnet34-LM"


# A felolvasandó mondatok. A hanglenyomat szövegfüggetlen, tehát nem a tartalom a
# lényeg, hanem hogy (1) elég nettó beszéd legyen (~30+ mp), (2) sokféle magyar hang
# szerepeljen (ö/ő/ü/ű, gy/ny/ty, sz/zs/cs, hosszú magánhangzók), és (3) a stílus a
# munka közbeni beszédhez hasonlítson: kérdések, utasítások, számok diktálása.
ENROLL_SENTENCES = [
    "Jó napot kívánok, kérem, mondja el, mi történt önnel.",
    "Mióta tart a fájdalom, hová sugárzik, és érez-e zsibbadást a kezében vagy a lábában?",
    "Szed rendszeresen gyógyszert, például vérnyomáscsökkentőt, vérhígítót vagy inzulint?",
    "Most meghallgatom a szívét és a tüdejét. Vegyen mély levegőt, és tartsa bent egy pillanatig.",
    "A bal kamra ejekciós frakciója ötvenöt százalék, regionális falmozgászavar nem látható.",
    "Az E per e vessző arány tizenkettő, a tricuspidalis regurgitáció sebessége két egész nyolc méter per szekundum.",
    "Hány éves korában műtötték, és volt-e azóta szövődménye vagy gyógyszerallergiás reakciója?",
    "Köszönöm a türelmét. Az eredményeket egy óra múlva megbeszéljük a hozzátartozójával együtt.",
]

MIN_SENTENCE_SPEECH = 2.0    # mp nettó beszéd mondatonként
MIN_TOTAL_SPEECH = 25.0      # mp nettó beszéd összesen
MAX_TRIES = 3                # ennyiszer kérjük újra ugyanazt a mondatot
# Tájékoztató küszöbök (mikrofonfüggők) – a figyelmeztetéshez, nem tiltáshoz
QUIET_PEAK = 0.03
CLIP_PEAK = 0.98
MIN_SNR = 4.0



def save_wav(path: str, audio: np.ndarray, sr: int = SR):
    pcm16 = np.clip(audio * 32767, -32768, 32767).astype(np.int16)
    with wave.open(path, "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(sr)
        f.writeframes(pcm16.tobytes())


def load_wav(path: str) -> np.ndarray:
    with wave.open(path, "rb") as f:
        if f.getframerate() != SR or f.getnchannels() != 1 or f.getsampwidth() != 2:
            raise ValueError(f"{path}: 16 kHz, mono, 16 bites WAV kell "
                             f"(ez {f.getframerate()} Hz, {f.getnchannels()} csatorna)")
        raw = f.readframes(f.getnframes())
    return np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32767


def cosine(a, b) -> float:
    a, b = np.ravel(a), np.ravel(b)
    return float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9))


class SpeakerID:
    """Hanglenyomat egy hangrészletből. A modell a helyi models/ mappából töltődik
    (egyszer letöltve: download_models.py), internet nélkül fut."""

    def __init__(self, device: str | None = None):
        from pyannote.audio import Inference, Model

        import local_models

        device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        # helyi mappából (download_models.py), futás közben nincs letöltés és nem kell HF_TOKEN
        model = Model.from_pretrained(local_models.embedding_model())
        self.inference = Inference(model, window="whole")
        self.inference.to(torch.device(device))

    def embed(self, audio: np.ndarray) -> np.ndarray:
        wav = torch.from_numpy(audio.astype(np.float32)).unsqueeze(0)
        return np.ravel(self.inference({"waveform": wav, "sample_rate": SR}))


def speech_only(audio: np.ndarray, vad_model) -> np.ndarray:
    """Csak a beszédrészek – a szünetek és a háttérzaj nem kerülnek a profilba."""
    if len(audio) == 0:
        return audio
    ts = get_speech_timestamps(torch.from_numpy(audio), vad_model,
                               sampling_rate=SR, speech_pad_ms=100)
    if not ts:
        return np.zeros(0, dtype=np.float32)
    return np.concatenate([audio[t["start"]:t["end"]] for t in ts])


def sentence_problems(raw: np.ndarray, speech: np.ndarray, noise_rms: float) -> tuple[list, list]:
    """(hibák, figyelmeztetések) – hiba esetén újra kell mondani."""
    errors, warns = [], []
    dur = len(speech) / SR
    if dur < MIN_SENTENCE_SPEECH:
        errors.append(f"túl kevés beszéd érzékelhető ({dur:.1f} mp)")
    if len(raw):
        peak = float(np.max(np.abs(raw)))
        if peak < QUIET_PEAK:
            errors.append("nagyon halk – beszéljen közelebb a mikrofonhoz, vagy emelje a mikrofon szintjét")
        if peak > CLIP_PEAK:
            warns.append("túlvezérelt, torzulhat – kicsit távolabbról vagy halkabban")
    if len(speech) and noise_rms > 0:
        snr = float(np.sqrt(np.mean(speech ** 2))) / noise_rms
        if snr < MIN_SNR:
            warns.append(f"a beszéd alig hangosabb a háttérzajnál (arány: {snr:.1f})")
    return errors, warns
