"""D) Hibatűrés — feldolgozás közben leállított szolgáltatás, majd újraindítás. Szintetikus tesztbetegen.

  D1  PDF-feldolgozás közben az Ollama leáll (a docproc már a modellt hívja), 30 mp múlva újraindul
  D2  hangfelvétel-feldolgozás közben a voice service (:8002) leáll, 20 mp múlva újraindul (VoiceLostJobs)
  D3  PDF-feldolgozás közben a docproc (:8001) leáll, 20 mp múlva újraindul (DocumentProcessingResume)

A PDF-ek az ai-service/eval szintetikus leletei; a hang egy meglévő tesztfelvétel másolata (--audio-recording).
Minden esetben: beavatkozás ideje, az állapot idővonala, a helyreállás ideje. A végén a tesztbeteg törlődik.
Windows/PowerShell: a folyamatokat port szerint állítja le (taskkill /T), és ugyanúgy indítja, mint a start.txt.

Futtatás (minden szolgáltatás fut):  python tools\\meresek_2026-11\\d_hibaturo.py [--only D1 D2 D3]
"""
import argparse
import json
import os
import shutil
import subprocess
import time
import urllib.request
from datetime import datetime

from common import ENV, HERE, ROOT, api, multipart, save_json, sql, sql_exec

DOCTOR_ID = 3
H = {"X-User-Id": str(DOCTOR_ID)}
LOGDIR = os.environ.get("MEDIAI_MERES_LOGDIR", str(HERE / "eredmenyek" / "naplo"))
DOCS = ROOT / "backend" / "data" / "documents"
LABS = ROOT / "ai-service" / "eval" / "data" / "labs"


def ps(cmd: str) -> str:
    return subprocess.run(["powershell", "-NoProfile", "-Command", cmd], capture_output=True, text=True).stdout


def port_pid(port: int) -> int | None:
    out = ps(f"(Get-NetTCPConnection -LocalPort {port} -State Listen -ErrorAction SilentlyContinue | "
             f"Select-Object -First 1).OwningProcess").strip()
    return int(out) if out.isdigit() else None


def kill_port(port: int):
    pid = port_pid(port)
    if pid:
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(pid)], capture_output=True)
    return pid


def start_service(name: str):
    os.makedirs(LOGDIR, exist_ok=True)
    if name == "docproc":
        exe, cwd = ROOT / "ai-service/docproc/.venv/Scripts/python.exe", ROOT / "ai-service/docproc"
        env = {**os.environ, "DOC_LLM_MODEL": "medgemma:latest"}
    else:
        exe, cwd = ROOT / "ai-service/voice/ai_env/Scripts/python.exe", ROOT / "ai-service/voice"
        env = dict(os.environ)
    log = open(os.path.join(LOGDIR, f"{name}_{datetime.now():%H%M%S}.log"), "w", encoding="utf-8")
    subprocess.Popen([str(exe), "app.py" if name == "docproc" else "service.py"], cwd=cwd, env=env,
                     stdout=log, stderr=subprocess.STDOUT, creationflags=0x08000000)  # CREATE_NO_WINDOW


def health(port: int):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=3) as r:
            return json.loads(r.read())
    except Exception:
        return None


def ollama_loaded() -> list[str]:
    try:
        with urllib.request.urlopen("http://127.0.0.1:11434/api/ps", timeout=3) as r:
            return [m["name"] for m in json.loads(r.read())["models"]]
    except Exception:
        return []


def stop_ollama():
    ps("Get-Process 'ollama app','ollama' -ErrorAction SilentlyContinue | Stop-Process -Force")


def start_ollama():
    ps(f"Start-Process '{os.environ['LOCALAPPDATA']}\\Programs\\Ollama\\ollama app.exe'")


class Timeline:
    def __init__(self):
        self.t0, self.events, self.last = time.time(), [], None

    def mark(self, what: str):
        self.events.append({"t": round(time.time() - self.t0, 1), "event": what})
        print(f"  {self.events[-1]['t']:7.1f} s  {what}", flush=True)

    def state(self, s: str):
        if s != self.last:
            self.mark(f"állapot: {s}")
            self.last = s

    def now(self):
        return round(time.time() - self.t0, 1)


def doc_state(did: int) -> str:
    d = api("GET", f"/api/documents/{did}", headers=H).json()
    return d["status"] + (f" ({(d.get('processingError') or '')[:120]})" if d["status"] == "FAILED" else "")


def follow(tl: Timeline, get, done, timeout: float, every: float = 2.0) -> str | None:
    t0 = time.time()
    while time.time() - t0 < timeout:
        s = get()
        tl.state(s)
        if done(s):
            return s
        time.sleep(every)
    tl.mark(f"határidő ({timeout:.0f} s) lejárt")
    return None


def upload_pdf(pid: int, name: str) -> int:
    body, ct = multipart({"patientId": pid}, {"file": (name, (LABS / name).read_bytes(), "application/pdf")})
    r = api("POST", "/api/documents", raw=body, content_type=ct, headers=H)
    assert r.status == 202, r
    return r.json()["id"]


def d1_ollama(pid: int) -> dict:
    print("D1 — Ollama leáll PDF-feldolgozás közben")
    tl = Timeline()
    did = upload_pdf(pid, "lab1_eeszt.pdf")
    tl.mark(f"PDF feltöltve (dokumentum {did})")
    # várunk, amíg a docproc ezt a dokumentumot dolgozza ÉS a modell betöltődött (a hívás folyamatban)
    t0 = time.time()
    while time.time() - t0 < 300:
        h = health(8001) or {}
        if h.get("current") == f"dokumentum {did}" and "medgemma:latest" in ollama_loaded():
            break
        time.sleep(1)
    else:
        raise RuntimeError("a docproc nem kezdte el a modellhívást 300 s alatt")
    time.sleep(3)
    stop_ollama()
    tl.mark("Ollama leállítva")
    follow(tl, lambda: doc_state(did), lambda s: not s.startswith("PROCESSING"), 30)
    time.sleep(max(0, 30 - (tl.now() - tl.events[-1]["t"])))
    start_ollama()
    t_restart = tl.now()
    tl.mark("Ollama újraindítva")
    final = follow(tl, lambda: doc_state(did), lambda s: not s.startswith("PROCESSING"), 300)
    res = {"case": "D1 Ollama", "document": did, "final_without_action": final}
    if final and final.startswith("FAILED"):
        # kézi újrapróbálás (a felhasználói felületen: „Újrafeldolgozás”)
        r = api("POST", f"/api/documents/{did}/reprocess", headers=H)
        tl.mark(f"kézi újrafeldolgozás kérve ({r.status})")
        t_re = tl.now()
        final2 = follow(tl, lambda: doc_state(did), lambda s: not s.startswith("PROCESSING"), 600)
        res.update(final_after_reprocess=final2, recovery_after_reprocess_s=round(tl.now() - t_re, 1))
    res["recovered_s_after_restart"] = round(tl.now() - t_restart, 1)
    res["timeline"] = tl.events
    return res


def d3_docproc(pid: int) -> dict:
    print("D3 — docproc leáll PDF-feldolgozás közben")
    tl = Timeline()
    did = upload_pdf(pid, "lab2_privat.pdf")
    tl.mark(f"PDF feltöltve (dokumentum {did})")
    t0 = time.time()
    while time.time() - t0 < 300:
        if (health(8001) or {}).get("current") == f"dokumentum {did}":
            break
        time.sleep(1)
    else:
        raise RuntimeError("a docproc nem kezdte el a dokumentumot 300 s alatt")
    time.sleep(5)
    pid_killed = kill_port(8001)
    tl.mark(f"docproc leállítva (PID {pid_killed})")
    follow(tl, lambda: doc_state(did), lambda s: False, 20)
    start_service("docproc")
    t_restart = tl.now()
    tl.mark("docproc újraindítva")
    while health(8001) is None and tl.now() - t_restart < 120:
        time.sleep(1)
    tl.mark("docproc /health válaszol")
    final = follow(tl, lambda: doc_state(did), lambda s: not s.startswith("PROCESSING"), 600)
    return {"case": "D3 docproc", "document": did, "final": final,
            "recovered_s_after_restart": round(tl.now() - t_restart, 1), "timeline": tl.events}


def rec_state(rid: int) -> str:
    r = api("GET", f"/api/recordings/{rid}", headers=H).json()
    return r["status"] + (f" ({(r.get('processingError') or '')[:120]})" if r["status"] == "FAILED" else "")


def d2_voice(pid: int, audio_rec: int) -> dict:
    print("D2 — voice service leáll hangfeldolgozás közben")
    tl = Timeline()
    src = sql(f"select audio_path, duration_sec, mode from recording where id = {audio_rec}")[0]
    path = DOCS / src["audio_path"]
    body, ct = multipart({"patientId": pid, "userId": DOCTOR_ID, "mode": src["mode"],
                          "durationSec": src["duration_sec"]},
                         {"audio": (path.name, path.read_bytes(), "audio/webm")})
    r = api("POST", "/api/recordings", raw=body, content_type=ct, headers=H)
    assert r.status == 202, r
    rid = r.json()["id"]
    tl.mark(f"felvétel feltöltve ({rid}, {src['duration_sec']} s hang)")
    t0 = time.time()
    while time.time() - t0 < 300:
        if (health(8002) or {}).get("current") == f"felvétel {rid}":
            break
        time.sleep(1)
    else:
        raise RuntimeError("a voice service nem kezdte el a felvételt 300 s alatt")
    time.sleep(10)  # az ASR már fut
    pid_killed = kill_port(8002)
    tl.mark(f"voice service leállítva (PID {pid_killed})")
    follow(tl, lambda: rec_state(rid), lambda s: False, 20)
    start_service("voice")
    t_restart = tl.now()
    tl.mark("voice service újraindítva")
    while health(8002) is None and tl.now() - t_restart < 300:
        time.sleep(2)
    tl.mark("voice /health válaszol")
    t_wait = time.time()
    while time.time() - t_wait < 180:
        if f"recording:{rid}" in ((health(8002) or {}).get("pending") or []) or \
                (health(8002) or {}).get("current") == f"felvétel {rid}":
            tl.mark("a felvétel újra a voice sorában (VoiceLostJobs átadta)")
            break
        time.sleep(2)
    final = follow(tl, lambda: rec_state(rid), lambda s: not s.startswith("PROCESSING"), 1800, every=5)
    extra = sql(f"""select (select count(*) from utterance where recording_id = {rid}) as utterances,
                           (select status from recording_extraction where recording_id = {rid} order by id desc limit 1) as findings,
                           (select status from recording_summary where recording_id = {rid} order by id desc limit 1) as summary""")[0]
    return {"case": "D2 voice", "recording": rid, "final": final, "after": extra,
            "recovered_s_after_restart": round(tl.now() - t_restart, 1), "timeline": tl.events}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", nargs="*", default=["D1", "D3", "D2"])
    ap.add_argument("--audio-recording", type=int, default=13, help="ennek a hangfájlja kerül újra feltöltésre")
    a = ap.parse_args()
    r = api("POST", "/api/patients", {"name": "Mérés Teszt D", "taj": None, "motherName": "Teszt Anya",
                                      "birthDate": "1970-01-01", "address": None}, headers=H)
    pid = r.json()["id"]
    out = {"date": datetime.now().isoformat(timespec="seconds"), "patient_id": pid, "cases": []}
    try:
        for case in a.only:
            fn = {"D1": lambda: d1_ollama(pid), "D2": lambda: d2_voice(pid, a.audio_recording),
                  "D3": lambda: d3_docproc(pid)}[case]
            try:
                out["cases"].append(fn())
            except Exception as e:  # noqa: BLE001
                out["cases"].append({"case": case, "error": f"{type(e).__name__}: {e}"})
            save_json("d_hibaturo.json", out)
            if not ollama_loaded() and case == "D1":
                start_ollama()
    finally:
        sql_exec(f"delete from pacient where id = {pid};")
        d = DOCS / str(pid)
        if d.exists():
            shutil.rmtree(d)
        out["patient_deleted"] = not sql(f"select id from pacient where id = {pid}")
        save_json("d_hibaturo.json", out)
    print(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
