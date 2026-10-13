"""Beállítások környezeti változóból (a Spring application.properties párja)."""
import os
from pathlib import Path


def _load_env_file(path: Path) -> None:
    """A repó gyökerében lévő közös .env betöltése (sablon: .env.example). A már beállított
    környezeti változót nem írja felül — így a Springgel ugyanazt a fájlt látjuk."""
    if not path.is_file():
        return
    with open(path, encoding="utf-8-sig") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            value = value.strip().strip('"').strip("'")
            if value:  # üres sor a sablonból → marad a kódbeli alapérték
                os.environ.setdefault(key.strip(), value)


_load_env_file(Path(__file__).resolve().parents[2] / ".env")

# A Spring ↔ Python közös titka, mindkét irányban az X-Internal-Token fejlécben. Nincs alapérték:
# beállítás nélkül nem indulunk (fail-closed) — üres tokennel egy üres fejléc is átmenne.
TOKEN = os.environ.get("MEDIAI_AI_TOKEN", "").strip()
if not TOKEN:
    raise RuntimeError("A MEDIAI_AI_TOKEN környezeti változó nincs beállítva (ugyanaz kell, mint a Springnél)")

HOST = os.environ.get("DOCPROC_HOST", "127.0.0.1")
PORT = int(os.environ.get("DOCPROC_PORT", "8001"))

# Ha meg van adva, csak ez alatti fájl dolgozható fel (a Spring mediai.storage.documents-dir-je,
# abszolút úttal). Védelem arra az esetre, ha a token kiszivárogna: tetszőleges fájl ne legyen olvasható.
_root = os.environ.get("MEDIAI_DOCUMENTS_ROOT")
DOCUMENTS_ROOT = Path(_root).resolve() if _root else None

OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434").rstrip("/")
# architecture.md: helyi fejlesztésnél MedGemma 4B (ollama pull medgemma:4b — vagy más letöltött modell)
LLM_MODEL = os.environ.get("DOC_LLM_MODEL", "medgemma:4b")
# A klinikai asszisztens (chat) modellje — külön állítható: a hosszú, sok forrású kontextusban a 4B-s
# MedGemma hurokba esett és rossz forrásra hivatkozott, a qwen3.5:9b nem (docs/clinical-chat.md)
CHAT_LLM_MODEL = os.environ.get("CHAT_LLM_MODEL", LLM_MODEL)
LLM_SEED = int(os.environ.get("DOC_LLM_SEED", "42"))
# Az Ollama alapértelmezett kontextusa (2–4k token) egy többoldalas leletet levágna
LLM_NUM_CTX = int(os.environ.get("DOC_LLM_NUM_CTX", "16384"))
LLM_TIMEOUT_SEC = int(os.environ.get("DOC_LLM_TIMEOUT_SEC", "600"))
# Egy hívás kimenetének felső határa (token): hurok / elszabadult generálás ellen
LLM_MAX_OUTPUT_TOKENS = int(os.environ.get("DOC_LLM_MAX_OUTPUT_TOKENS", "4096"))
# Ennél hosszabb szöveget nem küldünk a modellnek (a vége kimaradna — figyelmeztetéssel);
# a laborlelet szakaszonként megy, arra nem vonatkozik
LLM_MAX_CHARS = int(os.environ.get("DOC_LLM_MAX_CHARS", "40000"))

# Az Ollamában helyben letöltött embedding-modell neve (pl. qwen3-embedding:4b). Nincs alapérték:
# beállítás nélkül nem indulunk — ne essünk csendben vissza egy másik (letöltendő) modellre, és a
# document_chunk.embedding_model is ebből jön (különböző modellek vektorai nem keverhetők).
EMBEDDING_MODEL = os.environ.get("EMBEDDING_MODEL", "").strip()
if not EMBEDDING_MODEL:
    raise RuntimeError("Az EMBEDDING_MODEL környezeti változó nincs beállítva (az Ollamában letöltött"
                       " embedding-modell neve, pl. qwen3-embedding:4b — ld. .env.example)")

CHUNK_TARGET_CHARS = int(os.environ.get("CHUNK_TARGET_CHARS", "800"))
CHUNK_OVERLAP_CHARS = int(os.environ.get("CHUNK_OVERLAP_CHARS", "150"))

# A dokumentum extractor_version oszlopába kerül: a feldolgozó kód (prompt, utófeldolgozás) verziója
EXTRACTOR_VERSION = "docproc-14"

CALLBACK_TIMEOUT_SEC = 60
CALLBACK_RETRY_DELAYS_SEC = (2, 10, 30, 60)
