"""
Élő mikrofonos teszt – magyar beszédfelismerés megszólalásonként,
orvos/beteg azonosítással és opcionális tünetkinyeréssel (Ollama).

Ugyanabba a mappába tedd, mint a beszed_pipeline.py-t (onnan veszi a
módbeállításokat és a kinyerő függvényt).

Használat:
  python test.py --list-devices                              # mikrofonok listája
  python test.py --mode sbo --doctor-id drkovacs              # profil betöltése / ha nincs, felvétele, majd élő teszt
  python test.py --mode sbo --doctor-id drkovacs --extract    # leállítás után tünetkinyerés
  python test.py --mode sbo --doctor-id drkovacs --uj-profil  # hangprofil újrafelvétele
  python test.py --mode echo --model large-v3                 # modellek összehasonlítása

A hangprofilok kezelése a hangprofil.py-ban van (orvos_profilok/<doctor_id>.wav/.npy/.json).
"""
import argparse
import gc
import json
import os
import queue
import sys
import time
from collections import deque
from datetime import datetime

import numpy as np
import torch  # a faster-whisper ELŐTT importáld: betölti a CUDA/cuDNN könyvtárakat
import sounddevice as sd
from faster_whisper import WhisperModel
from silero_vad import VADIterator, load_silero_vad

from beszed_pipeline import MODES, extract
from hangprofil import SpeakerID, cosine, ensure_profile

SR = 16000
CHUNK = 512  # a Silero VAD 16 kHz-en 512 mintás blokkokat vár
DEFAULT_MODEL = "large-v3-turbo" #Maxdorger29/whisper-large-v3-hungarian-lora
# Csendre / zajra jellemző Whisper-hallucinációk – bővítsd, amit tapasztalsz
HALLUCINATIONS = ("feliratok", "feliratozta", "köszönöm, hogy megnézted", "iratkozz fel", "see ya", "see y'all", "s'ya", "siyah", "mipen", "i don't know")

def check_gpu() -> str:
    if not torch.cuda.is_available():
        print("FIGYELEM: a PyTorch nem lát CUDA-t – CPU-n fut, lassú lesz.")
        print("Telepítsd a cu128-as PyTorch-ot (lásd a leírást).")
        return "cpu"
    free, total = torch.cuda.mem_get_info()
    print(f"GPU: {torch.cuda.get_device_name(0)} | szabad VRAM: {free/1e9:.1f} / {total/1e9:.1f} GB")
    return "cuda"


def utterances(device_index, min_silence_ms: int, max_sec: float = 25.0):
    """Mikrofonról olvas, és minden befejezett megszólalást (szünetig) visszaad."""
    q: queue.Queue = queue.Queue()

    def callback(indata, frames, t, status):
        if status:
            print(status, file=sys.stderr)
        q.put(indata[:, 0].copy())

    vad = VADIterator(
        load_silero_vad(), sampling_rate=SR,
        min_silence_duration_ms=min_silence_ms, speech_pad_ms=200,
    )
    preroll = deque(maxlen=10)  # ~0,3 mp a beszéd kezdete előttről, hogy ne vágja le az első szótagot
    buf, recording = [], False
    pending = np.zeros(0, dtype=np.float32)

    with sd.InputStream(samplerate=SR, channels=1, dtype="float32",
                        blocksize=CHUNK, device=device_index, callback=callback):
        print("\n🎙  Hallgatom... (Ctrl+C a leállításhoz)\n")
        while True:
            pending = np.concatenate([pending, q.get()])
            while len(pending) >= CHUNK:
                chunk, pending = pending[:CHUNK], pending[CHUNK:]
                event = vad(torch.from_numpy(chunk))
                if recording:
                    buf.append(chunk)
                else:
                    preroll.append(chunk)
                if event and "start" in event and not recording:
                    recording, buf = True, list(preroll)
                too_long = recording and len(buf) * CHUNK / SR > max_sec
                if recording and ((event and "end" in event) or too_long):
                    recording = False
                    audio = np.concatenate(buf)
                    buf = []
                    preroll.clear()
                    if too_long:
                        vad.reset_states()
                    yield audio


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=MODES.keys(), default="sbo")
    ap.add_argument("--model", default=DEFAULT_MODEL,
                    help="HF repo (CTranslate2 formátum), helyi mappa, vagy pl. large-v3 / large-v3-turbo")
    ap.add_argument("--compute-type", default="float16",
                    help="float16 (biztos) vagy int8_float16 (kevesebb VRAM, ctranslate2>=4.7 kell RTX 50-en)")
    ap.add_argument("--uj-profil", action="store_true",
                    help="hangprofil újrafelvétele (a régi biztonsági mentésre kerül)")
    ap.add_argument("--nincs-profil", action="store_true",
                    help="orvos/beteg azonosítás kihagyása (csak beszédfelismerés)")
    ap.add_argument("--threshold", type=float, default=0.45,
                    help="hasonlósági küszöb az orvoshoz – a saját hangodon hangold be")
    ap.add_argument("--min-silence", type=int, default=700, help="ennyi ms szünet zár le egy megszólalást")
    ap.add_argument("--extract", action="store_true", help="leállítás után LLM-kinyerés Ollamával")
    ap.add_argument("--doctor-id", default="default",
                    help="a Medi-AI rendszerbeli orvos-azonosító – ez választja ki a profilt")
    ap.add_argument("--device", type=int, default=None, help="mikrofon indexe")
    ap.add_argument("--list-devices", action="store_true")
    args = ap.parse_args()

    if args.list_devices:
        print(sd.query_devices())
        return

    device = check_gpu()

    # Hangprofil: betöltés, vagy ha még nincs, vezetett felvétel.
    # A Whisper előtt fut, így a felvétel alatt nem foglal VRAM-ot a nagy modell.
    spk, doctor_emb = None, None
    if not args.nincs_profil:
        try:
            spk = SpeakerID(device)
            doctor_emb = ensure_profile(args.doctor_id, spk, args.device, force_new=args.uj_profil)
        except RuntimeError as e:
            print(f"Orvos/beteg azonosítás kimarad: {e}")
            spk = None

    print(f"ASR modell betöltése: {args.model} ({args.compute_type})...")
    t0 = time.perf_counter()
    model = WhisperModel(args.model, device=device, compute_type=args.compute_type)
    list(model.transcribe(np.zeros(SR, dtype=np.float32), language="hu")[0])  # bemelegítés
    print(f"Betöltve {time.perf_counter() - t0:.1f} mp alatt.")
    if device == "cuda":
        free, total = torch.cuda.mem_get_info()
        print(f"Szabad VRAM a modell után: {free/1e9:.1f} GB")

    chat, total_audio, total_proc = [], 0.0, 0.0
    try:
        for audio in utterances(args.device, args.min_silence):
            dur = len(audio) / SR
            if dur < 1.2:
                continue
            t0 = time.perf_counter()
            segments, _ = model.transcribe(
                audio, language="hu", beam_size=5, vad_filter=True,
                condition_on_previous_text=False,
                initial_prompt=MODES[args.mode]["initial_prompt"],
            )
            text = " ".join(s.text.strip() for s in segments).strip()
            proc = time.perf_counter() - t0
            if not text or any(h in text.lower() for h in HALLUCINATIONS):
                continue

            role, score = ("orvos" if args.mode == "echo" else "?"), None
            if doctor_emb is not None and dur >= 1.0:
                score = cosine(spk.embed(audio), doctor_emb)
                role = "orvos" if score >= args.threshold else "beteg"

            total_audio += dur
            total_proc += proc
            chat.append({"id": len(chat), "role": role, "duration": round(dur, 2), "text": text})
            score_txt = f"({score:.2f})" if score is not None else "      "
            print(f"{role.upper():>6} {score_txt} │ {text}")
            print(f"{'':>14}└ {dur:.1f} mp beszéd → {proc:.2f} mp feldolgozás")
    except KeyboardInterrupt:
        pass

    if not chat:
        print("\nNem készült átirat.")
        return
    print(f"\nÖsszesen {total_audio:.0f} mp beszéd, átlagos valós idejű faktor: "
          f"{total_proc / max(total_audio, 1e-6):.2f} (1 alatt gyorsabb a valós időnél)")

    out = f"elo_{args.mode}_{datetime.now():%Y%m%d_%H%M%S}.json"
    result = {"mode": args.mode, "model": args.model, "chat": chat}

    if args.extract:
        print("VRAM felszabadítása, majd kinyerés az Ollamával...")
        del model
        gc.collect()
        if device == "cuda":
            torch.cuda.empty_cache()
        result["extraction"] = extract(chat, args.mode)
        print(json.dumps(result["extraction"], ensure_ascii=False, indent=2))

    with open(out, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(f"Mentve: {out}")


if __name__ == "__main__":
    main()