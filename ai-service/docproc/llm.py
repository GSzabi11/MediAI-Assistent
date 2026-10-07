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
from textutil import join_wrapped

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
  "dozis" csak a szám (pl. "5" vagy "2,5"), "hatoanyag" csak ha a leletben szerepel.
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


def extract(doc_type: str, text: str) -> tuple[dict, list[str]]:
    """Strukturált kinyerés; (adat, figyelmeztetések). Laborleletnél szakaszonként, az
    eredmények összefésülve; a dátum az első szakasz nem üres dátuma (a worker úgyis a
    teljes szövegen ellenőrzi)."""
    if doc_type != "LAB":
        return _chat(doc_type, compact(text))

    warnings: list[str] = []
    merged: dict = {"datum": None, "eredmenyek": []}
    seen: set[tuple] = set()
    segments = lab_segments(join_wrapped(text))
    for i, segment in enumerate(segments, 1):
        data, w = _chat(doc_type, compact(segment))
        warnings.extend(f"{i}/{len(segments)}. szakasz: {x}" for x in w)
        if merged["datum"] is None and data.get("datum"):
            merged["datum"] = data["datum"]
        for row in data.get("eredmenyek") or []:
            key = (str(row.get("nev")).casefold(), str(row.get("ertek"))) if isinstance(row, dict) else None
            if key and key not in seen:
                seen.add(key)
                merged["eredmenyek"].append(row)
    return merged, warnings


def _chat(doc_type: str, text: str) -> tuple[dict, list[str]]:
    """Egy Ollama-hívás, streamelve. Ha a modell egy már kiadott elemet ismétel (hurok), vagy
    eléri a tokenkorlátot, a hívás leáll, és a hurok / csonkolás előtti teljes elemek
    maradnak meg — figyelmeztetéssel, nem 10 perces időtúllépéssel."""
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
        return _repair(out[:cut_at], doc_type), warnings
    if done_reason == "length":
        warnings.append("A modell kimenete elérte a tokenkorlátot; a csonka utolsó elem kimaradt")
        return _repair(out, doc_type), warnings
    try:
        data = json.loads(out)
    except json.JSONDecodeError as e:
        raise ValueError(f"A modell kimenete nem érvényes JSON: {e}") from e
    if not isinstance(data, dict):
        raise ValueError("A modell kimenete nem JSON-objektum")
    return data, warnings


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
