"""Közös GPU-zár a két Python-szolgáltatás (docproc, voice) között.

A fejlesztői gépen 8 GB VRAM van: a Whisper + pyannote és az Ollama-modell (MedGemma) nem fér el
egyszerre. Mindkét szolgáltatás saját soros sorral dolgozik, ez a zár pedig a kettőt is sorba
állítja: egyszerre egy GPU-feladat fut a gépen. Folyamatok közötti fájlzár (Windows: msvcrt,
máshol: fcntl), a lezárt fájl a rendszer ideiglenes könyvtárában van.

Használat:
    with gpu_lock("hangfelvétel 12"):
        ...
"""
import contextlib
import logging
import os
import tempfile
import time

log = logging.getLogger("gpulock")

LOCK_PATH = os.environ.get("MEDIAI_GPU_LOCK", os.path.join(tempfile.gettempdir(), "mediai-gpu.lock"))

if os.name == "nt":
    import msvcrt

    def _try_lock(fd: int) -> bool:
        try:
            msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
            return True
        except OSError:
            return False

    def _unlock(fd: int) -> None:
        os.lseek(fd, 0, os.SEEK_SET)
        msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
else:
    import fcntl

    def _try_lock(fd: int) -> bool:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return True
        except OSError:
            return False

    def _unlock(fd: int) -> None:
        fcntl.flock(fd, fcntl.LOCK_UN)


@contextlib.contextmanager
def gpu_lock(label: str, poll_sec: float = 0.5):
    fd = os.open(LOCK_PATH, os.O_RDWR | os.O_CREAT)
    try:
        waited = time.monotonic()
        logged = False
        while True:
            os.lseek(fd, 0, os.SEEK_SET)
            if _try_lock(fd):
                break
            if not logged:
                log.info("GPU foglalt (a másik szolgáltatás dolgozik) — %s vár", label)
                logged = True
            time.sleep(poll_sec)
        if logged:
            log.info("GPU megszerezve: %s (%.1f s várakozás)", label, time.monotonic() - waited)
        try:
            yield
        finally:
            _unlock(fd)
    finally:
        os.close(fd)
