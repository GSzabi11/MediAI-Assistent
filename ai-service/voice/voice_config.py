"""A hangszolgáltatás (service.py) beállításai, a repó gyökerében lévő közös .env-ből
(sablon: .env.example). A docproc/config.py párja; a valódi környezeti változó felülírja."""
import os
from pathlib import Path

import beszed_pipeline as bp
from beszed_pipeline import _load_env_file

_load_env_file()

# A közös .env-ben az OLLAMA_URL alapcím (http://localhost:11434, ahogy a docproc várja), a
# prototípus viszont a teljes /api/chat végpontot várja: igazítsuk, hogy mindkét alak működjön.
_ollama = os.environ.get("OLLAMA_URL", "http://localhost:11434").rstrip("/")
bp.OLLAMA_URL = _ollama if _ollama.endswith("/api/chat") else _ollama + "/api/chat"

# A Spring ↔ Python közös titka (X-Internal-Token, mindkét irányban). Nincs alapérték: beállítás
# nélkül nem indulunk (fail-closed), ugyanúgy, mint a docproc.
TOKEN = os.environ.get("MEDIAI_AI_TOKEN", "").strip()
if not TOKEN:
    raise RuntimeError("A MEDIAI_AI_TOKEN környezeti változó nincs beállítva (ugyanaz kell, mint a Springnél)")

HOST = os.environ.get("VOICE_HOST", "127.0.0.1")
PORT = int(os.environ.get("VOICE_PORT", "8002"))

# Ha meg van adva, csak ez alatti hangfájl olvasható / írható (a Spring mediai.storage.voice-dir-je,
# abszolút úttal). Védelem arra az esetre, ha a token kiszivárogna.
_root = os.environ.get("MEDIAI_VOICE_ROOT", "").strip()
VOICE_ROOT = Path(_root).resolve() if _root else None
# A betegfelvételek a dokumentumok mellett vannak ({documents-dir}/{beteg}/hangfelvetelek/): ha a
# docproc-nak meg van adva a MEDIAI_DOCUMENTS_ROOT, az is megengedett gyökér
_docs = os.environ.get("MEDIAI_DOCUMENTS_ROOT", "").strip()
DOCUMENTS_ROOT = Path(_docs).resolve() if _docs else None

# A hangszolgáltatás kódjának verziója: a producer/extractor mezőbe kerül
SERVICE_VERSION = "voice-1"

CALLBACK_TIMEOUT_SEC = 60
CALLBACK_RETRY_DELAYS_SEC = (2, 10, 30, 60)
