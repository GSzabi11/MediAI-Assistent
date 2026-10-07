"""PDF → szöveg. Csak natív (szöveges rétegű) PDF — OCR nincs (a feladat szerint nem kell)."""
import unicodedata
from pathlib import Path

import pymupdf


class NoTextLayerError(Exception):
    pass


def extract_text(path: Path) -> str:
    with pymupdf.open(path) as doc:
        pages = [page.get_text("text", sort=True) for page in doc]
    text = "\n\n".join(p.strip("\n") for p in pages)
    # NFC: az "ó" egy code point legyen (a Spring is code pointban számol);
    # NUL: a Postgres text oszlop nem tárolhatja; a kötőjelet a PDF-ek gyakran feltételes
    # elválasztóként (U+00AD) kódolják — láthatatlan lenne, és a "4,0 - 10,0" tartomány szétesne
    text = (unicodedata.normalize("NFC", text).replace("\r\n", "\n").replace("\x00", "")
            .replace("­", "-"))
    if not text.strip():
        raise NoTextLayerError("A PDF-nek nincs szöveges rétege (szkennelt dokumentum?) — OCR nincs")
    return text
