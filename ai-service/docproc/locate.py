"""MERRE a laborsornál: az eredmény sorának helye a PDF lapján (oldal + normált téglalap).

Determinisztikus: a PyMuPDF szó-koordinátáiból vizuális sorokat képez (azonos magasságban álló
szavak), és azt a sort keresi, amelyben a vizsgálat nevének eleje ÉS utána az érték áll —
ugyanaz a szabály, mint a szövegbeli ellenőrzésnél (extract.py). A teljes vizuális sort adja
vissza, hogy a frontend az egész táblázatsort emelje ki.
"""
from pathlib import Path

import pymupdf

from textutil import fold

# A sor-kiemelés kis ráhagyása a lap méretéhez képest
_PAD = 0.003


def _visual_rows(page) -> list[list[tuple]]:
    """A lap szavai sorokba csoportosítva: egy sor = függőlegesen átfedő szavak."""
    words = sorted(page.get_text("words"), key=lambda w: ((w[1] + w[3]) / 2, w[0]))
    rows: list[list[tuple]] = []
    for w in words:
        centre = (w[1] + w[3]) / 2
        if rows:
            last = rows[-1]
            top = min(x[1] for x in last)
            bottom = max(x[3] for x in last)
            if top <= centre <= bottom:
                last.append(w)
                continue
        rows.append([w])
    return [sorted(r, key=lambda w: w[0]) for r in rows]


def _value_variants(value: str) -> set[str]:
    v = fold(value)
    return {v, v.replace(".", ","), v.replace(",", ".")}


def _find_in_row(tokens: list[str], head: list[str], value: list[str], comparator: str | None) -> bool:
    """A név (szavai egymás után) és UTÁNA az érték az adott sorban."""
    for i in range(len(tokens) - len(head) + 1):
        if tokens[i:i + len(head)] != head:
            continue
        rest = tokens[i + len(head):]
        if len(value) > 1:  # többszavas szöveges érték
            if any(rest[j:j + len(value)] == value for j in range(len(rest) - len(value) + 1)):
                return True
            continue
        variants = _value_variants(value[0])
        for j, tok in enumerate(rest):
            bare = tok.lstrip("<>≤≥=")
            if bare not in variants:
                continue
            # cenzorált értéknél a jel is kell: "<7" egy szóban, vagy "<" közvetlenül előtte
            if comparator is None or tok != bare or (j > 0 and rest[j - 1] in ("<", ">", "<=", ">=", "≤", "≥")):
                return True
    return False


def find_row_box(path: Path, head: str, value: str, comparator: str | None = None) -> dict | None:
    """{page, x, y, width, height} (normált 0–1), vagy None. A név elejéből egyre rövidebb
    előtagot is próbál (a több sorba tört név második fele másik vizuális sorban áll), de
    legalább két szót — vagy az egész nevet, ha egyszavas."""
    head_tokens = fold(head).split()
    value_tokens = fold(value).split()
    if not head_tokens or not value_tokens:
        return None
    # szöveges érték ("Nem számolható") első szava is elég: a többi a következő sorba törhet
    if len(value_tokens) > 1 and not any(c.isdigit() for c in value):
        value_tokens = value_tokens[:1]
    min_len = min(2, len(head_tokens))
    with pymupdf.open(path) as doc:
        rows_by_page = [(n, page.rect, _visual_rows(page)) for n, page in enumerate(doc, 1)]
        for length in range(len(head_tokens), min_len - 1, -1):
            prefix = head_tokens[:length]
            for page_no, rect, rows in rows_by_page:
                for row in rows:
                    tokens = [fold(w[4]) for w in row]
                    if _find_in_row(tokens, prefix, value_tokens, comparator):
                        x0 = min(w[0] for w in row) / rect.width - _PAD
                        y0 = min(w[1] for w in row) / rect.height - _PAD
                        x1 = max(w[2] for w in row) / rect.width + _PAD
                        y1 = max(w[3] for w in row) / rect.height + _PAD
                        x0, y0 = max(0.0, x0), max(0.0, y0)
                        x1, y1 = min(1.0, x1), min(1.0, y1)
                        return {"page": page_no, "x": round(x0, 5), "y": round(y0, 5),
                                "width": round(x1 - x0, 5), "height": round(y1 - y0, 5)}
    return None


def add_source_boxes(path: Path, lab_results: list[dict]) -> None:
    """A belső "_head"/"_value"/"_comparator" mezőkből kiszámolja a "sourceBox"-ot, és a belső
    mezőket eltávolítja (nem mennek a Springnek)."""
    for r in lab_results:
        head, value, comparator = r.pop("_head", None), r.pop("_value", None), r.pop("_comparator", None)
        box = find_row_box(path, head, value, comparator) if head and value else None
        r["sourceBox"] = box
