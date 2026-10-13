"""A hangmodul modelljeinek EGYSZERI letöltése a projekt ai-service/models/ mappájába.

Ez az egyetlen lépés, amihez internet kell (és a pyannote-modellekhez a HF_TOKEN, a feltételek
elfogadásával a Hugging Face-en). Utána a hangszolgáltatás és a CLI-prototípus internet nélkül fut:
futás közben a Hub offline módban van, semmit nem tölt le.

    python download_models.py                 # az ASR_MODEL (.env) + diarizáció + hanglenyomat
    python download_models.py --asr large-v3-turbo
"""
import argparse
import os
import sys

# A letöltéshez a Hubnak online kell lennie (a többi modul alapból offline-ra állítja)
os.environ["HF_HUB_OFFLINE"] = "0"
os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")

from huggingface_hub import snapshot_download  # noqa: E402

from beszed_pipeline import _load_env_file  # noqa: E402

_load_env_file()
import local_models as lm  # noqa: E402


def fetch(repo: str, target: str, token: str | None) -> None:
    print(f"→ {repo}  ⇒  {target}")
    snapshot_download(repo_id=repo, local_dir=target, token=token)
    print("  kész")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--asr", default=os.environ.get("ASR_MODEL", "large-v3"),
                    help=f"faster-whisper modell: {', '.join(lm.WHISPER_REPOS)}")
    args = ap.parse_args()
    token = os.environ.get("HF_TOKEN") or None
    if args.asr not in lm.WHISPER_REPOS:
        sys.exit(f"Ismeretlen ASR-modell: {args.asr} (lehet: {', '.join(lm.WHISPER_REPOS)}, vagy egy mappaút az "
                 "ASR_MODEL-ben, amit nem kell letölteni)")
    lm.MODELS_DIR.mkdir(parents=True, exist_ok=True)
    fetch(lm.WHISPER_REPOS[args.asr], str(lm.MODELS_DIR / f"faster-whisper-{args.asr}"), token)
    if not token:
        sys.exit("A pyannote-modellekhez kell a HF_TOKEN (.env), és a feltételek elfogadása a Hugging Face-en.")
    fetch(lm.DIARIZATION_REPO, str(lm.MODELS_DIR / lm.DIARIZATION_REPO.split("/")[1]), token)
    fetch(lm.EMBEDDING_REPO, str(lm.MODELS_DIR / lm.EMBEDDING_REPO.split("/")[1]), token)
    print(f"\nMinden modell helyben: {lm.MODELS_DIR}")


if __name__ == "__main__":
    main()
