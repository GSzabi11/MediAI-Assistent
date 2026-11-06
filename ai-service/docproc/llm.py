"""Ollama-hívás típusfüggő prompttal, strukturált (JSON-séma kényszerített) kimenettel.

A modell csak JAVASOL: minden értéket, idézetet és dátumot az utófeldolgozás
(extract.py) determinisztikusan visszakeres a dokumentum szövegében, és ami nincs
benne szó szerint, az kimarad (figyelmeztetéssel).
"""
import hashlib
import json
import re

import requests

import config
from textutil import anchor_span, find_span, join_wrapped

LAB_SYSTEM = """Magyar nyelvű orvosi laboratóriumi leletből nyersz ki adatokat.
Szabályok:
- Csak azt add vissza, ami SZÓ SZERINT szerepel a leletben. Semmit ne számolj, ne kerekíts, ne egészíts ki.
- "datum": a mintavétel vagy a lelet kiadásának dátuma ÉÉÉÉ-HH-NN alakban; ha nincs, null.
- Minden vizsgálati sorhoz egy elem az "eredmenyek" tömbben:
  - "csoport": a vizsgálatcsoport fejléce (pl. "Vérkép", "Klinikai kémia"), ha van; különben null.
  - "nev": a vizsgálat neve pontosan úgy, ahogy a leletben áll.
  - "ertek": az eredmény pontosan úgy, ahogy a leletben áll (pl. "4,52", "<0,5", "negatív").
  - "mertekegyseg": a mértékegység, ha van; különben null.
  - "referencia": a referenciatartomány pontosan úgy, ahogy a leletben áll (pl. "3,5 - 5,1" vagy "< 5.0"); ha nincs, null.
- A lelet MINDEN vizsgálati sorát add vissza, a cenzorált (pl. ">90") és szöveges eredményűeket is.
- Szöveges eredménynél (pl. "Negatív", "Normális", "kevés") a referencia is lehet szöveg — azt is add vissza.
- Az alcím (pl. "KLINIKAI KÉMIA", "Vizelet üledék", "Automata mikroszkópos értékelés") NEM vizsgálat:
  az alatta álló sorok külön-külön vizsgálatok, mindegyiknek a saját sorában álló neve a "nev".
- Ha egy vizsgálat neve a táblázatban több sorba tört, a teljes nevet add vissza.
- Fejlécet, beteg- és orvosadatot, megjegyzést ne adj vissza eredményként."""

LAB_SCHEMA = {
    "type": "object",
    "properties": {
        "datum": {"type": ["string", "null"]},
        "eredmenyek": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "csoport": {"type": ["string", "null"]},
                    "nev": {"type": "string"},
                    "ertek": {"type": "string"},
                    "mertekegyseg": {"type": ["string", "null"]},
                    "referencia": {"type": ["string", "null"]},
                },
                "required": ["csoport", "nev", "ertek", "mertekegyseg", "referencia"],
            },
        },
    },
    "required": ["datum", "eredmenyek"],
}

AMBULANCE_CATEGORIES = ["DIAGNOZIS", "PANASZ", "ANAMNEZIS", "STATUSZ", "VIZSGALAT",
                        "TERAPIA", "GYOGYSZER", "ALLERGIA", "JAVASLAT"]

# Lapos séma, mintapéldával: a kis modellek (4B) a beágyazott, nullázható objektumokon
# összeomlottak (egyetlen elemet adtak vissza, rossz kategóriához kötött allergiával).
AMBULANCE_SYSTEM = """Magyar nyelvű ambuláns lapból (szakorvosi/sürgősségi leletből) nyersz ki klinikai kulcsadatokat.
Menj végig a leleten szakaszonként, és MINDEN kulcsadatot add vissza külön elemként az "adatok" tömbben:
minden panaszt, anamnézis-adatot, státusz-értéket, vizsgálati eredményt, diagnózist, terápiát,
MINDEN gyógyszert külön elemként, MINDEN allergiát külön elemként, és minden javaslatot.

Egy elem mezői:
- "kategoria": DIAGNOZIS, PANASZ, ANAMNEZIS, STATUSZ, VIZSGALAT, TERAPIA, GYOGYSZER, ALLERGIA vagy JAVASLAT.
- "allitas": rövid, tárgyszerű magyar mondat az adatról. Tagadott adatot a tagadással együtt fogalmazz meg.
- "idezet": a leletből SZÓ SZERINT kimásolt, összefüggő szövegrész, amely az állítást alátámasztja.
  Ne fogalmazd át, ne javítsd az elírást, ne fűzz össze távoli részeket.
- "bizonytalansag": LOW (egyértelműen kimondja), MEDIUM (értelmezni kellett), HIGH (kétértelmű, feltételezés).
- "gyogyszer_nev", "hatoanyag", "dozis", "dozis_egyseg", "alkalmazas": csak GYOGYSZER elemnél, különben null.
  "dozis" csak az egyszeri adag száma (pl. "5" vagy "2,5"; "1x1,25 mg" → "1,25"), "hatoanyag" csak ha a
  leletben szerepel, "alkalmazas" az adagolás úgy, ahogy a leletben áll (pl. "este", "R-E: 2x", "E: 1x").
- Gyógyszerlista ("Gyógyszerei:", "Javasolt gyógyszeres terápia:", "Th:") MINDEN sora külön GYOGYSZER elem,
  akkor is, ha javaslat — a "T." a tabletta jele, nem a név része.
- "allergen": csak ALLERGIA elemnél az anyag neve, különben null. Ha a lelet szerint nincs ismert allergia,
  ne adj vissza ALLERGIA elemet.
"datum": a vizsgálat (ellátás) dátuma ÉÉÉÉ-HH-NN alakban; ha nincs, null.

Példa (más betegről): "Panasz: fejfájás. Gyógyszer: Algopyrin 500 mg szükség szerint."
→ {"datum": null, "adatok": [
 {"kategoria":"PANASZ","allitas":"Fejfájás","idezet":"fejfájás","bizonytalansag":"LOW","gyogyszer_nev":null,"hatoanyag":null,"dozis":null,"dozis_egyseg":null,"alkalmazas":null,"allergen":null},
 {"kategoria":"GYOGYSZER","allitas":"Algopyrin 500 mg szükség szerint","idezet":"Algopyrin 500 mg szükség szerint","bizonytalansag":"LOW","gyogyszer_nev":"Algopyrin","hatoanyag":null,"dozis":"500","dozis_egyseg":"mg","alkalmazas":"szükség szerint","allergen":null}]}"""

_NULLABLE = {"type": ["string", "null"]}
_AMBULANCE_FIELDS = ["gyogyszer_nev", "hatoanyag", "dozis", "dozis_egyseg", "alkalmazas", "allergen"]

AMBULANCE_SCHEMA = {
    "type": "object",
    "properties": {
        "datum": _NULLABLE,
        "adatok": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "kategoria": {"type": "string", "enum": AMBULANCE_CATEGORIES},
                    "allitas": {"type": "string"},
                    "idezet": {"type": "string"},
                    "bizonytalansag": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]},
                    **{f: _NULLABLE for f in _AMBULANCE_FIELDS},
                },
                "required": ["kategoria", "allitas", "idezet", "bizonytalansag", *_AMBULANCE_FIELDS],
            },
        },
    },
    "required": ["datum", "adatok"],
}

PROMPTS = {
    "LAB": (LAB_SYSTEM, LAB_SCHEMA),
    "AMBULANCE": (AMBULANCE_SYSTEM, AMBULANCE_SCHEMA),
}


# Laborleletet szakaszonként küldünk a modellnek: egy hosszú táblázatnál a 4B-s modell
# ~37 sor után ismétlő hurokba esett (a lista elejéről újrakezdte), és a kontextus végéig
# generált (10 perc → időtúllépés). Rövid szakaszon ez nem alakul ki, és egy hívás gyors.
LAB_SEGMENT_ROWS = 25

# Az ambuláns lapot is szakaszonként (bekezdéshatáron) küldjük: egy 4 oldalas lapnál (12 ezer
# karakter) a 4B-s modell az 1. oldal néhány eleme után hurokba esett, a végén álló „Javasolt
# gyógyszeres terápia” listáig el sem jutott — egyetlen gyógyszer sem lett kinyerve.
AMBULANCE_SEGMENT_CHARS = 2500

# Oldalszámláló ("1/4") a lap élőfejében/élőlábában
_PAGE_COUNTER = re.compile(r"(?<![\d/])\d{1,3}\s*/\s*\d{1,3}(?![\d/])")

# Egy elem azonosítója a kimenetben — ha ugyanaz újra megjelenik, a modell hurokba esett
_ITEM_KEY = {
    "LAB": re.compile(r'"nev"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"ertek"\s*:\s*"((?:[^"\\]|\\.)*)"'),
    "AMBULANCE": re.compile(r'"idezet"\s*:\s*"((?:[^"\\]|\\.)*)"'),
}
_ARRAY_KEY = {"LAB": "eredmenyek", "AMBULANCE": "adatok"}
_LONG_SPACE = re.compile(r"[ \t]{3,}")


def prompt_hash(doc_type: str) -> str:
    """A prompt + séma + szakaszolás + feldolgozó-verzió ujjlenyomata (HOGY): ugyanaz a hash =
    ugyanaz a kinyerési eljárás."""
    system, schema = PROMPTS[doc_type]
    payload = json.dumps({"system": system, "schema": schema, "extractor": config.EXTRACTOR_VERSION,
                          "lab_segment_rows": LAB_SEGMENT_ROWS if doc_type == "LAB" else None,
                          "ambulance_segment_chars": AMBULANCE_SEGMENT_CHARS if doc_type == "AMBULANCE" else None,
                          "segment_continuations": SEGMENT_CONTINUATIONS if doc_type == "AMBULANCE" else None,
                          "max_output_tokens": config.LLM_MAX_OUTPUT_TOKENS},
                         ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def compact(text: str) -> str:
    """A modellnek küldött alak: a táblázat hosszú szóközsorai két szóközre (kevesebb token).
    Csak a modell bemenete — a visszakeresés és minden offset az eredeti szövegen fut."""
    return _LONG_SPACE.sub("  ", text)


def lab_segments(text: str, rows: int = LAB_SEGMENT_ROWS) -> list[str]:
    """Sorhatáron vágott szakaszok, mindegyikben legfeljebb `rows` számot tartalmazó sor
    (a táblázat sorai). A fejléc (beteg, dátumok) az első szakaszba kerül."""
    segments: list[str] = []
    current: list[str] = []
    data_rows = 0
    for line in text.splitlines():
        if data_rows >= rows and any(c.isdigit() for c in line):
            segments.append("\n".join(current))
            current, data_rows = [], 0
        current.append(line)
        if any(c.isdigit() for c in line):
            data_rows += 1
    if any(l.strip() for l in current):
        segments.append("\n".join(current))
    return segments


def strip_page_furniture(text: str) -> str:
    """Az oldalszámlálós élőláb/élőfej ("Gödri Szabolcs   3/4   Esetszám: 5716047") sorai a
    körülöttük álló üres sorokkal együtt kimaradnak, így az oldaltörés nem vág ketté egy listát
    vagy bekezdést. Élőlábnak az a sor számít, amely az oldalszám nélkül legalább kétszer
    előfordul. Csak a modell bemenete — az offsetek az eredeti szövegen futnak."""
    lines = text.splitlines()
    keys = [_page_key(l) if _PAGE_COUNTER.search(l) else None for l in lines]
    repeated = {k for k in keys if k is not None and keys.count(k) >= 2}
    out: list[str] = []
    skip_blank = False
    for line, key in zip(lines, keys):
        if key in repeated:
            while out and not out[-1].strip():
                out.pop()
            skip_blank = True
            continue
        if skip_blank and not line.strip():
            continue
        skip_blank = False
        out.append(line)
    return "\n".join(out)


def _page_key(line: str) -> str:
    return re.sub(r"\s+", " ", _PAGE_COUNTER.sub("#", line)).strip()


def ambulance_segments(text: str, max_chars: int = AMBULANCE_SEGMENT_CHARS) -> list[str]:
    """Bekezdéshatáron (üres sor) vágott, legfeljebb ~`max_chars` hosszú szakaszok; az ennél
    hosszabb bekezdés sorhatáron törik. Az élőláb előtte kimarad (strip_page_furniture)."""
    blocks: list[str] = []
    for block in re.split(r"\n\s*\n", strip_page_furniture(text)):
        if not block.strip():
            continue
        while len(block) > max_chars:
            cut = block.rfind("\n", 0, max_chars)
            if cut <= 0:
                cut = max_chars
            blocks.append(block[:cut])
            block = block[cut:].lstrip("\n")
        blocks.append(block)
    segments: list[str] = []
    for block in blocks:
        if segments and len(segments[-1]) + 2 + len(block) <= max_chars:
            segments[-1] += "\n\n" + block
        else:
            segments.append(block)
    return segments


def extract(doc_type: str, text: str) -> tuple[dict, list[str]]:
    """Strukturált kinyerés; (adat, figyelmeztetések). Szakaszonként, az elemek összefésülve;
    a dátum az első szakasz nem üres dátuma (a worker úgyis a teljes szövegen ellenőrzi)."""
    if doc_type == "AMBULANCE":
        return _extract_segmented(doc_type, ambulance_segments(text), "adatok",
                                  lambda item: str(item.get("idezet")).casefold(), _ambulance_rest)
    if doc_type != "LAB":
        data, warnings, _ = _chat(doc_type, compact(text))
        return data, warnings
    return _extract_segmented(doc_type, lab_segments(join_wrapped(text)), "eredmenyek",
                              lambda row: (str(row.get("nev")).casefold(), str(row.get("ertek"))))


def _extract_segmented(doc_type: str, segments: list[str], array_key: str, item_key,
                       rest_of=None) -> tuple[dict, list[str]]:
    """`rest_of(szakasz, adat)`: ha a modell a szakasz közepén elakadt (hurok, tokenkorlát), a szakasz
    fel nem dolgozott maradéka — ez újra a modellhez megy (legfeljebb SEGMENT_CONTINUATIONS-ször)."""
    warnings: list[str] = []
    merged: dict = {"datum": None, array_key: []}
    seen: set = set()
    for i, segment in enumerate(segments, 1):
        part = compact(segment)
        for attempt in range(SEGMENT_CONTINUATIONS + 1):
            data, w, cut = _chat(doc_type, part)
            warnings.extend(f"{i}/{len(segments)}. szakasz: {x}" for x in w)
            if merged["datum"] is None and data.get("datum"):
                merged["datum"] = data["datum"]
            for item in data.get(array_key) or []:
                key = item_key(item) if isinstance(item, dict) else None
                if key and key not in seen:
                    seen.add(key)
                    merged[array_key].append(item)
            rest = rest_of(part, data) if cut and rest_of and attempt < SEGMENT_CONTINUATIONS else None
            if rest is None:
                break
            warnings.append(f"{i}/{len(segments)}. szakasz: az elakadás utáni részt ({len(rest)} karakter) "
                            f"újra elküldtük a modellnek")
            part = rest
    return merged, warnings


# Ha a modell egy ambuláns szakasz közepén hurokba esett vagy elérte a tokenkorlátot, a hurok utáni
# elemek (a lap végén álló gyógyszerlista, diagnózisok) elvesztek. Az Ollama 0.40-re frissítése óta
# (2026-10-23; a gemma3-at azóta a llama.cpp futtatja) a 4B-s modell gyakrabban esik hurokba, és egy
# elemként idéz egész bekezdést — a 10-14-i állapothoz képest kevesebb gyógyszer és panasz jött ki.
# Ezért a szakasz maradékát (az utolsó visszakereshető idézet utáni részt) újra elküldjük.
SEGMENT_CONTINUATIONS = 2
_MIN_REST_CHARS = 60


def _ambulance_rest(part: str, data: dict) -> str | None:
    """A szakasz része az utolsó, a szakaszban megtalált idézet vége után; None, ha nincs érdemi
    maradék, vagy a modell egyetlen visszakereshető idézetet sem adott (különben ugyanazt küldenénk)."""
    end = 0
    for item in data.get("adatok") or []:
        quote = str(item.get("idezet") or "").strip() if isinstance(item, dict) else ""
        span = (find_span(part, quote) or anchor_span(part, quote)) if quote else None
        if span:
            end = max(end, span[1])
    rest = part[end:].lstrip(" \t.,;:)/")
    if end == 0 or sum(c.isalnum() for c in rest) < _MIN_REST_CHARS:
        return None
    return rest


def _chat(doc_type: str, text: str) -> tuple[dict, list[str], bool]:
    """Egy Ollama-hívás, streamelve. Ha a modell egy már kiadott elemet ismétel (hurok), vagy
    eléri a tokenkorlátot, a hívás leáll, és a hurok / csonkolás előtti teljes elemek
    maradnak meg — figyelmeztetéssel, nem 10 perces időtúllépéssel. A harmadik érték: elakadt-e
    (hurok / tokenkorlát), azaz lehet-e még fel nem dolgozott rész a szövegben."""
    system, schema = PROMPTS[doc_type]
    key_re = _ITEM_KEY[doc_type]
    out = ""
    seen: set[tuple] = set()
    scanned = 0
    cut_at: int | None = None
    done_reason = None
    with requests.post(
        f"{config.OLLAMA_URL}/api/chat",
        json={
            "model": config.LLM_MODEL,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": "A dokumentum szövege:\n\n" + text},
            ],
            "format": schema,
            "stream": True,
            # Reprodukálhatóság: fix seed, temperature 0 (a seed a producer-be is bekerül);
            # num_predict: egy elszabadult generálás se tarthasson percekig
            "options": {"temperature": 0, "seed": config.LLM_SEED, "num_ctx": config.LLM_NUM_CTX,
                        "num_predict": config.LLM_MAX_OUTPUT_TOKENS},
        },
        stream=True,
        timeout=config.LLM_TIMEOUT_SEC,  # két stream-darab közötti várakozás felső határa
    ) as resp:
        resp.raise_for_status()
        for line in resp.iter_lines():
            if not line:
                continue
            chunk = json.loads(line)
            if "error" in chunk:
                raise ValueError(f"Ollama-hiba: {chunk['error']}")
            out += chunk.get("message", {}).get("content", "")
            for m in key_re.finditer(out, scanned):
                key = tuple(g.casefold() for g in m.groups())
                if key in seen:
                    cut_at = m.start()
                    break
                seen.add(key)
                scanned = m.end()
            if cut_at is not None:
                break  # a with-blokk lezárja a kapcsolatot → az Ollama leállítja a generálást
            if chunk.get("done"):
                done_reason = chunk.get("done_reason")
                break

    warnings: list[str] = []
    if cut_at is not None:
        warnings.append("A modell ismétlő hurokba esett; a hurok előtti elemeket használtuk fel")
        return _repair(out[:cut_at], doc_type), warnings, True
    if done_reason == "length":
        warnings.append("A modell kimenete elérte a tokenkorlátot; a csonka utolsó elem kimaradt")
        return _repair(out, doc_type), warnings, True
    try:
        data = json.loads(out)
    except json.JSONDecodeError as e:
        raise ValueError(f"A modell kimenete nem érvényes JSON: {e}") from e
    if not isinstance(data, dict):
        raise ValueError("A modell kimenete nem JSON-objektum")
    return data, warnings, False


def _repair(partial: str, doc_type: str) -> dict:
    """Félbeszakadt kimenetből az utolsó TELJES elemig tartó rész lezárva ("…}]}").
    Ha egyetlen teljes elem sincs, üres lista."""
    array_key = _ARRAY_KEY[doc_type]
    start = partial.find("[")
    if start < 0:
        return {"datum": None, array_key: []}
    end = partial.rfind("}")
    while end > start:
        try:
            data = json.loads(partial[:end + 1] + "]}")
            if isinstance(data, dict):
                return data
        except json.JSONDecodeError:
            pass
        end = partial.rfind("}", start, end)
    head = partial[:start + 1] + "]}"
    try:
        data = json.loads(head)
        return data if isinstance(data, dict) else {"datum": None, array_key: []}
    except json.JSONDecodeError:
        return {"datum": None, array_key: []}
