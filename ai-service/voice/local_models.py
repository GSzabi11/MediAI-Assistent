"""Helyi modellek: futás közben SEMMI nem töltődik le, internet nélkül is működik.

A modelleket egyszer, internettel kell letölteni a projekt `ai-service/models/` mappájába
(gitignore-olt): `python download_models.py`. Futás közben a Hugging Face Hub offline módban van
(HF_HUB_OFFLINE=1, ld. beszed_pipeline.py eleje), így ha egy modell hiányzik, érthető hibával áll
le — nem kezd el csendben letölteni.

| Mire | Alapértelmezett helyi mappa | Felülírható |
|---|---|---|
| beszédfelismerés (faster-whisper, CTranslate2) | models/faster-whisper-<ASR_MODEL> | ASR_MODEL = mappa útja |
| diarizáció (pyannote pipeline) | models/speaker-diarization-community-1 | VOICE_DIARIZATION_MODEL |
| hanglenyomat (wespeaker) | models/wespeaker-voxceleb-resnet34-LM | VOICE_EMBEDDING_MODEL |

A Silero VAD a pip-csomagban van (silero_vad/data), a faster-whisper VAD-ja is a csomagban.
"""
import os
from pathlib import Path

MODELS_DIR = Path(os.environ.get("MEDIAI_MODELS_DIR", "").strip()
                  or Path(__file__).resolve().parent.parent / "models").resolve()

# Hugging Face repó → helyi mappanév (a download_models.py ezekbe tölt)
WHISPER_REPOS = {
    "large-v3": "Systran/faster-whisper-large-v3",
    "large-v3-turbo": "mobiuslabsgmbh/faster-whisper-large-v3-turbo",
    "medium": "Systran/faster-whisper-medium",
    "small": "Systran/faster-whisper-small",
}
DIARIZATION_REPO = "pyannote/speaker-diarization-community-1"
EMBEDDING_REPO = "pyannote/wespeaker-voxceleb-resnet34-LM"


class ModelMissingError(RuntimeError):
    """A modell nincs letöltve a helyi mappába."""


def _missing(what: str, path: Path) -> ModelMissingError:
    return ModelMissingError(
        f"A(z) {what} modell nincs meg helyben ({path}). Internet-kapcsolattal egyszer futtasd: "
        "python download_models.py (ai-service/voice), utána internet nélkül is működik.")


def _local_dir(env_name: str, default: Path, what: str) -> str:
    raw = os.environ.get(env_name, "").strip()
    path = Path(raw).expanduser() if raw else default
    if not path.is_dir():
        raise _missing(what, path)
    return str(path)


def asr_model(name: str) -> str:
    """A faster-whisper modell mappája. ASR_MODEL lehet mappaút vagy modellnév (pl. large-v3)."""
    candidate = Path(name).expanduser()
    if candidate.is_dir():
        return str(candidate)
    path = MODELS_DIR / f"faster-whisper-{name}"
    if not path.is_dir():
        raise _missing(f"beszédfelismerő ({name})", path)
    return str(path)


def diarization_model() -> str:
    return _local_dir("VOICE_DIARIZATION_MODEL", MODELS_DIR / DIARIZATION_REPO.split("/")[1], "diarizációs")


def embedding_model() -> str:
    return _local_dir("VOICE_EMBEDDING_MODEL", MODELS_DIR / EMBEDDING_REPO.split("/")[1], "hanglenyomat")
