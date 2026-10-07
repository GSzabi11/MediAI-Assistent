"""Szöveg-normalizálás és pozíció-keresés.

Minden offset Unicode code pointban értendő — a Python str indexe pontosan ez, és a Spring
(Document.textSpan) is így számol. A normalizált szövegben talált egyezést mindig
visszavetítjük az EREDETI szöveg pozícióira.
"""
import re
import unicodedata

_WS = re.compile(r"\s+")


def fold_char(ch: str) -> str:
    """Egy karakter összevetési alakja: ékezet nélkül, kisbetűsen (pl. 'Ó' → 'o')."""
    decomposed = unicodedata.normalize("NFKD", ch)
    return "".join(c for c in decomposed if not unicodedata.combining(c)).casefold()


def fold(text: str) -> str:
    """Ékezet-, kis/nagybetű- és whitespace-független alak (csak összevetéshez)."""
    return _WS.sub(" ", "".join(fold_char(c) for c in text)).strip()


def _folded_with_map(text: str) -> tuple[str, list[int]]:
    """A fold() alak + minden folded karakterhez az eredeti szövegbeli indexe.

    A whitespace-sorozat egyetlen szóközzé esik össze; a sorvégi elválasztás
    ("labora-\\ntóriumi") is egybeolvad, mert PDF-ből gyakran így jön a szöveg.
    """
    out: list[str] = []
    index: list[int] = []
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch == "-" and i + 1 < n and text[i + 1] in "\r\n":
            # sorvégi elválasztójel: "-" + sortörés + szóközök kimarad
            j = i + 1
            while j < n and text[j].isspace():
                j += 1
            i = j
            continue
        if ch.isspace():
            if out and out[-1] != " ":
                out.append(" ")
                index.append(i)
            i += 1
            continue
        for f in fold_char(ch):
            out.append(f)
            index.append(i)
        i += 1
    return "".join(out), index


def contains_phrase(text: str, phrase: str) -> bool:
    folded, _ = _folded_with_map(text)
    return fold(phrase) in folded


def find_span(text: str, quote: str, start_hint: int = 0) -> tuple[int, int] | None:
    """A szó szerinti idézet [start, end) helye az eredeti szövegben, vagy None.

    Először pontos egyezés, utána whitespace/ékezet/kisbetű-független keresés. Ha az
    idézet többször is előfordul, a start_hint utáni első (különben az első) a találat.
    """
    quote = quote.strip()
    if not quote:
        return None
    pos = text.find(quote, start_hint)
    if pos < 0:
        pos = text.find(quote)
    if pos >= 0:
        return pos, pos + len(quote)

    folded, index = _folded_with_map(text)
    needle = fold(quote)
    if not needle:
        return None
    hint = next((k for k, orig in enumerate(index) if orig >= start_hint), 0)
    k = folded.find(needle, hint)
    if k < 0:
        k = folded.find(needle)
    if k < 0:
        return None
    start = index[k]
    end = index[k + len(needle) - 1] + 1
    return start, end


_CELL = re.compile(r"\S+(?: \S+)*")


def cells(line: str) -> list[tuple[int, int, str]]:
    """Egy táblázatsor cellái (kezdet, vég, szöveg): a PDF szövegrétegében az oszlopokat
    legalább két szóköz választja el, egy cellán belül egy."""
    return [(m.start(), m.end(), m.group()) for m in _CELL.finditer(line)]


def join_wrapped(text: str) -> str:
    """A táblázatban több sorba tört cella folytatását ("(TSH)", "térfoga", "gyűjtött (A)",
    "számolható") visszailleszti az előző (számot tartalmazó) sor AHHOZ a cellájához, amelyik
    fölött áll (oszlop-átfedés, különben a legközelebbi betűs cella). Így a név és az eredmény
    is egyben marad: "Vizelet mikroalbumin 24h ürítés  Nem számolható  < 30.0" — enélkül a
    modell a referenciát olvasta ki eredménynek. Csoportfejléc (nagybetűvel kezdődik) nem
    folytatás. A modell bemenete és a sor-párosítás használja; az offsetek az eredeti szövegre
    vonatkoznak."""
    out: list[str] = []
    for line in text.splitlines():
        stripped = line.strip()
        is_continuation = (stripped and not any(c.isdigit() for c in stripped)
                           and (stripped[0] == "(" or stripped[0].islower()))
        if not (is_continuation and out and any(c.isdigit() for c in out[-1])):
            out.append(line)
            continue
        prev = out[-1]
        c_start = len(line) - len(line.lstrip())
        c_end = c_start + len(stripped)
        prev_cells = cells(prev)

        def overlap(cell: tuple[int, int, str]) -> int:
            return min(cell[1], c_end) - max(cell[0], c_start)

        lettered = [c for c in prev_cells if any(ch.isalpha() for ch in c[2])] or prev_cells
        best = max(prev_cells, key=overlap)
        if overlap(best) <= 0:
            centre = (c_start + c_end) / 2
            best = min(lettered, key=lambda c: abs((c[0] + c[1]) / 2 - centre))
        out[-1] = prev[:best[1]] + " " + stripped + prev[best[1]:]
    return "\n".join(out)
