"""32 kbit/s-os Opus próba: egy meglévő tesztfelvétel újrakódolva (ffmpeg, libopus 32k) a teljes szerveroldali úton
(faster-whisper, pyannote, átnézés). Az eredeti (≈128 kbit/s) felvétel átiratához mérve: megszólalás- és beszélőszám,
szóeltérés (WER, nyers). Szintetikus tesztbeteg, a végén törölve.

  ffmpeg -i <eredeti.webm> -ac 1 -c:a libopus -b:a 32k -application voip proba32k.webm
  python tools\\meresek_2026-11\\bitrata_proba.py --file proba32k.webm --ref-recording 13
"""
import argparse
import shutil
from pathlib import Path

from common import ROOT, api, multipart, save_json, sql, sql_exec, wait_until
from wer import tokens_raw, wer

H = {"X-User-Id": "3"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--file", required=True, type=Path)
    ap.add_argument("--ref-recording", type=int, default=13)
    a = ap.parse_args()
    ref = sql(f"select mode, duration_sec, audio_path from recording where id = {a.ref_recording}")[0]
    orig = ROOT / "backend" / "data" / "documents" / ref["audio_path"]
    pid = api("POST", "/api/patients", {"name": "Mérés Teszt Bitráta", "taj": None, "motherName": "Teszt Anya",
                                        "birthDate": "1970-01-01", "address": None}, headers=H).json()["id"]
    out = {}
    try:
        data = a.file.read_bytes()
        body, ct = multipart({"patientId": pid, "userId": 3, "mode": ref["mode"], "durationSec": ref["duration_sec"]},
                             {"audio": (a.file.name, data, "audio/webm")})
        r = api("POST", "/api/recordings", raw=body, content_type=ct, headers=H)
        assert r.status == 202, r
        rid = r.json()["id"]
        st = wait_until(lambda: (lambda x: x["status"] not in ("PROCESSING", "RECORDING") and x)(
            api("GET", f"/api/recordings/{rid}", headers=H).json()), 1800, 5)
        q = lambda i: sql(f"select turn_id, speaker, coalesce(asr_text, text) as asr, text from utterance "
                          f"where recording_id = {i} order by turn_id")
        new, old = q(rid), q(a.ref_recording)
        join = lambda us, k: " ".join(u[k] for u in us)
        w = wer(tokens_raw(join(old, "asr")), tokens_raw(join(new, "asr")))
        w.pop("_ops")
        out = {
            "status": st and st["status"], "error": st and st.get("processingError"),
            "bytes_per_sec_32k": round(len(data) / ref["duration_sec"], 1),
            "bytes_per_sec_orig": round(orig.stat().st_size / ref["duration_sec"], 1),
            "utterances": {"orig": len(old), "32k": len(new)},
            "speakers": {"orig": sorted({u["speaker"] for u in old}), "32k": sorted({u["speaker"] for u in new})},
            "asr_word_diff_vs_orig": w,
        }
    finally:
        sql_exec(f"delete from pacient where id = {pid};")
        d = ROOT / "backend" / "data" / "documents" / str(pid)
        if d.exists():
            shutil.rmtree(d)
    save_json("bitrata_proba.json", out)
    print(out)


if __name__ == "__main__":
    main()
