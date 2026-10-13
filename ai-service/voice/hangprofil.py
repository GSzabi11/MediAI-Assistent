"""
Orvosi hangprofil – felvétel, mentés, betöltés.

Működés (ensure_profile):
  - Ha az orvosnak még nincs profilja: kiír néhány mondatot, egyenként felolvastatja,
    minden mondatot ellenőriz (hangerő, beszédhossz), majd elmenti.
  - Ha van profil: egyszerűen betölti.
  - Ha van WAV, de hiányzik a beágyazás, vagy azóta beágyazó modellt váltottatok:
    a WAV-ból újraszámolja, nem kell újra felvenni.

Fájlok orvosonként (orvos_profilok/):
  <doctor_id>.wav   – a mondatok beszédrészei összefűzve, 16 kHz, mono, 16 bit PCM
  <doctor_id>.npy   – hanglenyomat (embedding) a gyors napi összehasonlításhoz
  <doctor_id>.json  – metaadatok: dátum, beágyazó modell, nettó beszédidő, minőség

Önállóan is futtatható:
  python hangprofil.py --doctor-id drkovacs            # betölt, vagy ha nincs, felvesz
  python hangprofil.py --doctor-id drkovacs --uj       # új felvétel (a régi mentésre kerül)
  python hangprofil.py --doctor-id drkovacs --teszt    # élő próba: mennyire ismer fel
"""
import argparse
import json
import os
import queue
import shutil
import sys
import time
import wave
from datetime import datetime

import numpy as np
import sounddevice as sd
import torch
from silero_vad import VADIterator, get_speech_timestamps, load_silero_vad

from beszed_pipeline import _load_env_file
from profil_core import (CHUNK, CLIP_PEAK, EMBED_MODEL, ENROLL_SENTENCES, MAX_TRIES,  # noqa: F401
                         MIN_SENTENCE_SPEECH, MIN_SNR, MIN_TOTAL_SPEECH, QUIET_PEAK, SR, SpeakerID,
                         cosine, load_wav, save_wav, sentence_problems, speech_only)

_load_env_file()

PROFILE_DIR = "orvos_profilok"


# ---------------------------------------------------------------------------
# Fájlkezelés
# ---------------------------------------------------------------------------
def profile_paths(doctor_id: str) -> dict:
    os.makedirs(PROFILE_DIR, exist_ok=True)
    base = os.path.join(PROFILE_DIR, doctor_id)
    return {"wav": base + ".wav", "npy": base + ".npy", "json": base + ".json"}


def backup_profile(paths: dict):
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    for p in paths.values():
        if os.path.exists(p):
            shutil.move(p, f"{p}.bak_{stamp}")


# ---------------------------------------------------------------------------
# Hanglenyomat
# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# Felvétel
# ---------------------------------------------------------------------------
def _level_bar(chunk: np.ndarray, width: int = 30) -> str:
    rms = float(np.sqrt(np.mean(chunk ** 2)))
    n = min(width, int(rms * width * 12))
    return "█" * n + "·" * (width - n)


def record_until_pause(vad_model, device, max_sec: float = 20.0,
                       end_silence_ms: int = 1200, start_timeout: float = 10.0) -> np.ndarray:
    """Addig vesz fel, amíg a beszéd után ~1,2 mp szünet nem jön (vagy lejár az idő)."""
    q: queue.Queue = queue.Queue()

    def callback(indata, frames, t, status):
        q.put(indata[:, 0].copy())

    vad = VADIterator(vad_model, sampling_rate=SR,
                      min_silence_duration_ms=end_silence_ms, speech_pad_ms=150)
    chunks, pending = [], np.zeros(0, dtype=np.float32)
    started, t0, n = False, time.time(), 0
    with sd.InputStream(samplerate=SR, channels=1, dtype="float32",
                        blocksize=CHUNK, device=device, callback=callback):
        while True:
            try:
                pending = np.concatenate([pending, q.get(timeout=1)])
            except queue.Empty:
                continue
            while len(pending) >= CHUNK:
                chunk, pending = pending[:CHUNK], pending[CHUNK:]
                chunks.append(chunk)
                n += 1
                if n % 5 == 0:
                    state = "beszéd" if started else "várok"
                    print(f"\r    🎙 [{_level_bar(chunk)}] {state}   ", end="", flush=True)
                event = vad(torch.from_numpy(chunk))
                if event and "start" in event:
                    started = True
                if event and "end" in event and started:
                    vad.reset_states()
                    print("\r" + " " * 60 + "\r", end="")
                    return np.concatenate(chunks)
            elapsed = time.time() - t0
            if (not started and elapsed > start_timeout) or elapsed > max_sec:
                vad.reset_states()
                print("\r" + " " * 60 + "\r", end="")
                return np.concatenate(chunks) if chunks else np.zeros(0, dtype=np.float32)


def measure_noise(device, seconds: float = 2.0) -> float:
    print(f"\n  Háttérzaj mérése {seconds:.0f} mp – kérem, most maradjon csendben...")
    rec = sd.rec(int(seconds * SR), samplerate=SR, channels=1, dtype="float32", device=device)
    sd.wait()
    return float(np.sqrt(np.mean(rec[:, 0] ** 2)))


def enroll(doctor_id: str, spk: SpeakerID, device=None) -> np.ndarray:
    paths = profile_paths(doctor_id)
    vad_model = load_silero_vad()

    print(f"\n{'=' * 70}\nHANGPROFIL FELVÉTELE – {doctor_id}\n{'=' * 70}")
    print("Pár mondatot fog felolvasni, egyenként. Tippek a jó profilhoz:")
    print("  • ugyanazzal a mikrofonnal és távolságból, ahogy munka közben fogja használni")
    print("  • természetes tempóban és hangerővel, ahogy egy beteggel beszél")
    print("  • a mondat végén tartson egy kis szünetet – a felvétel magától leáll")

    noise = measure_noise(device)
    if noise > 0.02:
        print(f"  ⚠ Viszonylag zajos környezet (zajszint: {noise:.3f}). Ha lehet, keressen csendesebb helyet.")
    else:
        print(f"  ✓ Háttérzaj rendben ({noise:.3f})")

    segments: list[np.ndarray] = []
    total = len(ENROLL_SENTENCES)
    skipped_in_row = 0
    for idx, sentence in enumerate(ENROLL_SENTENCES, 1):
        accepted = False
        for attempt in range(1, MAX_TRIES + 1):
            print(f"\n[{idx}/{total}]  \"{sentence}\"")
            input("    Enter, majd olvassa fel...")
            raw = record_until_pause(vad_model, device)
            speech = speech_only(raw, vad_model)
            errors, warns = sentence_problems(raw, speech, noise)
            for w in warns:
                print(f"    ⚠ {w}")
            if not errors:
                print(f"    ✓ rendben ({len(speech) / SR:.1f} mp beszéd)")
                segments.append(speech)
                accepted = True
                break
            print("    ✗ " + "; ".join(errors))
            if attempt < MAX_TRIES:
                print("    Kérem, mondja el újra.")
            else:
                print("    Kihagyom ezt a mondatot.")
        skipped_in_row = 0 if accepted else skipped_in_row + 1
        # Ha már az elején semmi nem sikerül, valószínűleg a mikrofonnal van baj –
        # ne kelljen az összes mondaton végigszenvedni.
        if (idx == 1 and not accepted) or skipped_in_row >= 2:
            raise RuntimeError(
                "A felvétel nem sikerül – valószínűleg mikrofonprobléma. Ellenőrizze, hogy a "
                "megfelelő eszköz van-e kiválasztva (--list-devices, majd --device N), nincs-e "
                "némítva, és a Windows engedélyezi-e a mikrofon használatát a Pythonnak."
            )

    # Összhang-ellenőrzés: minden mondat ugyanattól a beszélőtől származik-e?
    # (kiszűri, ha valaki közbeszólt, vagy egy mondat főleg zaj volt)
    if len(segments) >= 3:
        embs = [spk.embed(s) for s in segments]
        sims = []
        for i, e in enumerate(embs):
            others = np.mean([x for j, x in enumerate(embs) if j != i], axis=0)
            sims.append(cosine(e, others))
        median = float(np.median(sims))
        keep = [i for i, s in enumerate(sims) if s >= median - 0.15]
        dropped = len(segments) - len(keep)
        if dropped:
            print(f"\n  ⚠ {dropped} mondat hangja eltért a többitől (zaj vagy közbeszólás?), kihagyom.")
        segments = [segments[i] for i in keep]
        sims = [sims[i] for i in keep]
    else:
        sims = []

    audio = np.concatenate(segments) if segments else np.zeros(0, dtype=np.float32)
    net = len(audio) / SR
    if net < MIN_SENTENCE_SPEECH * 2:
        raise RuntimeError("Nem gyűlt össze elég beszéd a profilhoz. Ellenőrizze a mikrofont (--list-devices).")
    if net < MIN_TOTAL_SPEECH:
        print(f"\n  ⚠ Csak {net:.0f} mp használható beszéd gyűlt össze (ajánlott: {MIN_TOTAL_SPEECH:.0f}+). "
              f"A profil működik, de pontatlanabb lehet – később vegye fel újra: --uj")

    emb = spk.embed(audio)
    save_wav(paths["wav"], audio)
    np.save(paths["npy"], emb)
    meta = {
        "doctor_id": doctor_id,
        "created": datetime.now().isoformat(timespec="seconds"),
        "embed_model": EMBED_MODEL,
        "sample_rate": SR,
        "net_speech_sec": round(net, 1),
        "sentences_used": len(segments),
        "noise_rms": round(noise, 4),
        "self_similarity_min": round(min(sims), 3) if sims else None,
        "input_device": sd.query_devices(device, "input")["name"],
    }
    with open(paths["json"], "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    print(f"\n✓ Profil elmentve ({net:.0f} mp beszéd, {len(segments)} mondat): {paths['wav']}")
    return emb


# ---------------------------------------------------------------------------
# Betöltés vagy létrehozás
# ---------------------------------------------------------------------------
def ensure_profile(doctor_id: str, spk: SpeakerID, device=None, force_new: bool = False) -> np.ndarray:
    """Betölti az orvos hangprofilját; ha nincs, felveszi. Mindig a hanglenyomatot adja vissza."""
    paths = profile_paths(doctor_id)

    if force_new:
        if os.path.exists(paths["wav"]):
            backup_profile(paths)
            print("A korábbi profil biztonsági mentésre került (.bak_<időbélyeg>).")
        return enroll(doctor_id, spk, device)

    if not os.path.exists(paths["wav"]):
        print(f"\nMég nincs hangprofil ehhez az orvoshoz: {doctor_id}")
        return enroll(doctor_id, spk, device)

    meta = {}
    if os.path.exists(paths["json"]):
        with open(paths["json"], encoding="utf-8") as f:
            meta = json.load(f)

    if os.path.exists(paths["npy"]) and meta.get("embed_model") == EMBED_MODEL:
        emb = np.load(paths["npy"])
        print(f"✓ Hangprofil betöltve: {doctor_id} "
              f"({meta.get('net_speech_sec', '?')} mp beszéd, felvéve: {meta.get('created', '?')})")
        return emb

    # Van WAV, de nincs (érvényes) hanglenyomat – pl. modellváltás után
    reason = "hiányzik a hanglenyomat" if not os.path.exists(paths["npy"]) else \
        f"a beágyazó modell megváltozott ({meta.get('embed_model')} → {EMBED_MODEL})"
    print(f"Hangprofil újraszámolása a meglévő felvételből: {reason}")
    emb = spk.embed(load_wav(paths["wav"]))
    np.save(paths["npy"], emb)
    meta.update(embed_model=EMBED_MODEL, reembedded=datetime.now().isoformat(timespec="seconds"))
    meta.setdefault("doctor_id", doctor_id)
    with open(paths["json"], "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    print(f"✓ Hangprofil frissítve: {doctor_id}")
    return emb


def live_test(emb: np.ndarray, spk: SpeakerID, device=None, rounds: int = 5):
    """Rövid próba: mondjon pár mondatot saját magaként, majd kérjen meg valakit."""
    vad_model = load_silero_vad()
    print("\nÉlő próba – beszéljen, a rendszer kiírja a hasonlóságot a profilhoz (Ctrl+C: vége).")
    print("Próbálja ki saját hangján, majd kérjen meg valaki mást is.\n")
    try:
        for _ in range(rounds * 4):
            raw = record_until_pause(vad_model, device, end_silence_ms=800)
            speech = speech_only(raw, vad_model)
            if len(speech) / SR < 1.0:
                continue
            sim = cosine(spk.embed(speech), emb)
            bar = "█" * max(0, int(sim * 30))
            print(f"  hasonlóság: {sim:5.2f}  {bar}")
    except KeyboardInterrupt:
        pass
    print("\nA küszöböt (elo_teszt.py --threshold) a saját és a más hangján mért értékek közé állítsa.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--doctor-id", required=True, help="a Medi-AI rendszerbeli orvos-azonosító")
    ap.add_argument("--uj", action="store_true", help="új felvétel (a régi biztonsági mentésre kerül)")
    ap.add_argument("--teszt", action="store_true", help="élő próba a profillal")
    ap.add_argument("--device", type=int, default=None, help="mikrofon indexe")
    ap.add_argument("--list-devices", action="store_true")
    args = ap.parse_args()

    if args.list_devices:
        print(sd.query_devices())
        return
    try:
        spk = SpeakerID()
    except RuntimeError as e:
        sys.exit(str(e))
    emb = ensure_profile(args.doctor_id, spk, args.device, force_new=args.uj)
    if args.teszt:
        live_test(emb, spk, args.device)


if __name__ == "__main__":
    main()