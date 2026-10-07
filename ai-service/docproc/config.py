"""Beállítások környezeti változóból (a Spring application.properties párja)."""
import os
from pathlib import Path

# A Spring ↔ Python közös titka, mindkét irányban az X-Internal-Token fejlécben
TOKEN = os.environ.get("MEDIAI_AI_TOKEN", "dev-only-change-me")

HOST = os.environ.get("DOCPROC_HOST", "127.0.0.1")
PORT = int(os.environ.get("DOCPROC_PORT", "8001"))

# Ha meg van adva, csak ez alatti fájl dolgozható fel (a Spring mediai.storage.documents-dir-je,
# abszolút úttal). Védelem arra az esetre, ha a token kiszivárogna: tetszőleges fájl ne legyen olvasható.
_root = os.environ.get("MEDIAI_DOCUMENTS_ROOT")
DOCUMENTS_ROOT = Path(_root).resolve() if _root else None

OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434").rstrip("/")
# architecture.md: helyi fejlesztésnél MedGemma 4B (ollama pull medgemma:4b — vagy más letöltött modell)
LLM_MODEL = os.environ.get("DOC_LLM_MODEL", "medgemma:4b")
LLM_SEED = int(os.environ.get("DOC_LLM_SEED", "42"))
# Az Ollama alapértelmezett kontextusa (2–4k token) egy többoldalas leletet levágna
LLM_NUM_CTX = int(os.environ.get("DOC_LLM_NUM_CTX", "16384"))
LLM_TIMEOUT_SEC = int(os.environ.get("DOC_LLM_TIMEOUT_SEC", "600"))
# Egy hívás kimenetének felső határa (token): hurok / elszabadult generálás ellen
LLM_MAX_OUTPUT_TOKENS = int(os.environ.get("DOC_LLM_MAX_OUTPUT_TOKENS", "4096"))
# Ennél hosszabb szöveget nem küldünk a modellnek (a vége kimaradna — figyelmeztetéssel);
# a laborlelet szakaszonként megy, arra nem vonatkozik
LLM_MAX_CHARS = int(os.environ.get("DOC_LLM_MAX_CHARS", "40000"))

EMBEDDING_MODEL = os.environ.get("EMBEDDING_MODEL", "BAAI/bge-m3")
# "cuda" / "cpu"; üresen: cuda, ha elérhető
EMBEDDING_DEVICE = os.environ.get("EMBEDDING_DEVICE") or None

CHUNK_TARGET_CHARS = int(os.environ.get("CHUNK_TARGET_CHARS", "800"))
CHUNK_OVERLAP_CHARS = int(os.environ.get("CHUNK_OVERLAP_CHARS", "150"))

# A dokumentum extractor_version oszlopába kerül: a feldolgozó kód (prompt, utófeldolgozás) verziója
EXTRACTOR_VERSION = "docproc-11"

CALLBACK_TIMEOUT_SEC = 60
CALLBACK_RETRY_DELAYS_SEC = (2, 10, 30, 60)
