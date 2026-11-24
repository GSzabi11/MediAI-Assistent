"""Közös segédek a 2026-11-es IDK-mérésekhez: SQL a Docker-konténerben (psql), REST a futó Springen.

Csak a Python standard könyvtárát használja (bármelyik venv-vel vagy a rendszer-Pythonnal fut).
A kimenetbe (jelentés) SOHA nem kerül betegnév, TAJ vagy dokumentumszöveg — csak összesített szám.
"""
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
RESULTS = HERE / "eredmenyek"
DB_CONTAINER = os.environ.get("MEDIAI_DB_CONTAINER", "mediai-assistent-db-1")
API = os.environ.get("MEDIAI_API", "http://localhost:8080").rstrip("/")


def env_file() -> dict:
    out = {}
    p = ROOT / ".env"
    if p.exists():
        for line in p.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    return out


ENV = env_file()


def sql(query: str) -> list[dict]:
    """A lekérdezés sorai JSON-ként (json_agg), psql-lel a konténerben."""
    wrapped = f"select coalesce(json_agg(_q), '[]'::json) from ({query}) _q"
    res = subprocess.run(["docker", "exec", "-i", DB_CONTAINER, "psql", "-U", ENV.get("POSTGRES_USER", "postgres"),
                          "-d", ENV.get("POSTGRES_DB", "mydatabase"), "-At", "-v", "ON_ERROR_STOP=1"],
                         input=wrapped.encode("utf-8"), capture_output=True, check=True)
    return json.loads(res.stdout.decode("utf-8") or "[]")


def sql_exec(statement: str) -> str:
    res = subprocess.run(["docker", "exec", "-i", DB_CONTAINER, "psql", "-U", ENV.get("POSTGRES_USER", "postgres"),
                          "-d", ENV.get("POSTGRES_DB", "mydatabase"), "-At", "-v", "ON_ERROR_STOP=1"],
                         input=statement.encode("utf-8"), capture_output=True, check=True)
    return res.stdout.decode("utf-8")


class Resp:
    def __init__(self, status: int, body: bytes, headers):
        self.status, self.body, self.headers = status, body, headers

    def json(self):
        return json.loads(self.body.decode("utf-8")) if self.body else None

    def __repr__(self):
        return f"<{self.status} {self.body[:200]!r}>"


def api(method: str, path: str, body=None, headers: dict | None = None, raw: bytes | None = None,
        content_type: str | None = None, timeout: int = 60) -> Resp:
    h = dict(headers or {})
    data = raw
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        h["Content-Type"] = "application/json"
    elif content_type:
        h["Content-Type"] = content_type
    req = urllib.request.Request(API + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return Resp(r.status, r.read(), r.headers)
    except urllib.error.HTTPError as e:
        return Resp(e.code, e.read(), e.headers)


def multipart(fields: dict, files: dict) -> tuple[bytes, str]:
    """files: név -> (fájlnév, bájtok, content-type)"""
    b = uuid.uuid4().hex
    out = bytearray()
    for k, v in fields.items():
        out += f"--{b}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode("utf-8")
    for k, (fn, data, ct) in files.items():
        out += (f"--{b}\r\nContent-Disposition: form-data; name=\"{k}\"; filename=\"{fn}\"\r\n"
                f"Content-Type: {ct}\r\n\r\n").encode("utf-8") + data + b"\r\n"
    out += f"--{b}--\r\n".encode("utf-8")
    return bytes(out), f"multipart/form-data; boundary={b}"


def wait_until(check, timeout: float, every: float = 2.0):
    """check() értéke, amint igaz; különben None a határidő után."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        v = check()
        if v:
            return v
        time.sleep(every)
    return None


def save_json(name: str, data) -> Path:
    RESULTS.mkdir(exist_ok=True)
    p = RESULTS / name
    p.write_text(json.dumps(data, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    return p


def stats(xs: list[float]) -> dict:
    xs = sorted(x for x in xs if x is not None)
    if not xs:
        return {"n": 0}
    n = len(xs)
    med = xs[n // 2] if n % 2 else (xs[n // 2 - 1] + xs[n // 2]) / 2
    return {"n": n, "mean": round(sum(xs) / n, 2), "median": round(med, 2), "min": round(xs[0], 2),
            "max": round(xs[-1], 2)}
