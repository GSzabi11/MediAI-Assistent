"""A WER-felvételek hangjának újrafeldolgozása (új felvételként, külön tesztbetegnél), pl. ha az átnézés nem futott le.

  python tools\\meresek_2026-11\\wer_ujrafuttatas.py --recordings 18 20 21 22 23 --refs W1 W2 W3 W4 W5
Kiírja a kész wer.py --map parancsot. A tesztbeteg megmarad (--delete-after: törli a mérés után).
"""
import argparse

from common import ROOT, api, multipart, sql, wait_until


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--recordings", nargs="+", type=int, required=True)
    ap.add_argument("--refs", nargs="+", required=True)
    a = ap.parse_args()
    src = {r["id"]: r for r in sql(f"select id, user_id, mode, duration_sec, audio_path from recording "
                                   f"where id in ({','.join(map(str, a.recordings))})")}
    uid = src[a.recordings[0]]["user_id"]
    H = {"X-User-Id": str(uid)}
    pid = api("POST", "/api/patients", {"name": "WER Teszt újrafuttatás", "taj": None, "motherName": "Teszt Anya",
                                        "birthDate": "1970-01-01", "address": None}, headers=H).json()["id"]
    print("tesztbeteg:", pid, flush=True)
    pairs = []
    for rid, ref in zip(a.recordings, a.refs):
        s = src[rid]
        p = ROOT / "backend" / "data" / "documents" / s["audio_path"]
        body, ct = multipart({"patientId": pid, "userId": uid, "mode": s["mode"], "durationSec": s["duration_sec"]},
                             {"audio": (p.name, p.read_bytes(), "audio/webm")})
        r = api("POST", "/api/recordings", raw=body, content_type=ct, headers=H)
        assert r.status == 202, r
        new = r.json()["id"]
        st = wait_until(lambda: (lambda x: x["status"] not in ("PROCESSING", "RECORDING") and x)(
            api("GET", f"/api/recordings/{new}", headers=H).json()), 1800, 5)
        warn = sql(f"select processing_warnings w, review_model m from recording where id = {new}")[0]
        print(f"{rid} → {new} ({ref}): {st and st['status']}, átnéző: {warn['m']}, "
              f"figyelmeztetés: {[x[:90] for x in (warn['w'] or []) if 'átnézés' in x.lower()]}", flush=True)
        pairs.append(f"{new}={ref}")
    print("python tools\\meresek_2026-11\\wer.py --map " + " ".join(pairs))


if __name__ == "__main__":
    main()
