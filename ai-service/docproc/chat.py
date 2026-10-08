"""Klinikai asszisztens (RAG-chat): egy orvosi kérdés megválaszolása a beteg adataiból, forrásokkal.

Folyamat (docs/clinical-chat.md):
  1. a kérdés BGE-M3 vektora (a document_chunk-okéval azonos modell);
  2. a Spring retrieve végpontja visszaadja a beteg legközelebbi dokumentum-darabjait (pgvector —
     az adatbázist csak a Spring éri el);
  3. a modell számozott forrásokat kap (darabok + a Spring strukturált tényei), és [n] jelekkel
     hivatkozik rájuk;
  4. determinisztikus utófeldolgozás: pontokra bontás, a [n] jelek → {kind, id} forrás, nem létező
     sorszám elhagyása, ellenőrizetlen szám → figyelmeztetés.
A bizonytalansági kategóriát a Spring számolja a forrás-lefedettségből (nem a modell önértékelése).
"""
import hashlib
import json
import logging
import re

import requests

import config
import embedding
from summarize import _clause_key, stream_messages, unverified_numbers
from textutil import fold

log = logging.getLogger("docproc.chat")

SYSTEM = """Klinikai asszisztens vagy egy magyar kórházban. Egy orvos kérdez az egyik betegéről.
Szabályok:
- KIZÁRÓLAG a megadott FORRÁSOK és HÁTTÉR alapján válaszolj. Ha a válasz nincs bennük, írd:
  "A rendelkezésre álló adatokban erről nincs információ."
- Minden állítás végére írd a felhasznált forrás sorszámát szögletes zárójelben, pl. [3] vagy [2][5].
  Csak a felsorolt sorszámokra hivatkozz.
- Számot (laborértéket, méretet, dátumot) csak pontosan, a forrásból idézz; ne számolj új értéket.
- Ha több szempont van, számozott pontokba szedd (1., 2., ...), a pont elején félkövér rövid címmel
  (pl. **Máj**:). Előtte legfeljebb egy rövid, kettősponttal végződő bevezető mondat lehet.
- Tömören, magyarul, legfeljebb 8 pontban.
- Diagnózist, terápiát ne állíts biztosként: feltételes mód, a döntés az orvosé.
- A [nem ellenőrzött] jelölésű tényeknél ezt a válaszban is jelezd.
- A beteg személyes adatait (név, születési dátum, cím, TAJ) ne ismételd."""

# A formátum-emlékeztető a kérdés UTÁN áll: a kis modell a hosszú kontextus végére figyel a legjobban
# (a rendszerpromptban lévő hivatkozási szabályt az első próbán teljesen figyelmen kívül hagyta).
FORMAT_REMINDER = """Válaszolj a KÉRDÉSRE a fenti források alapján, tömören. Minden pont és mondat végén a
felhasznált forrás sorszáma szögletes zárójelben — e nélkül a válasz nem használható. Minta:
1. **Kálium**: 5,6 mmol/L, a referencia felett (2026.07.14.) [4].
2. **Máj**: megnagyobbodott, zsírmáj képe [1][2]."""

# Enyhe ismétlés-büntetés: az erős (1.2) a forrás-jeleket és a pontosan idézett számokat is
# büntetné. A hurkot a streamelt tagmondat-figyelés fogja meg.
_OPTIONS = {"temperature": 0, "repeat_penalty": 1.05, "repeat_last_n": 128}
MAX_ANSWER_TOKENS = 900
MAX_POINTS = 30
RETRIEVE_TIMEOUT_SEC = 30

_KIND_LABEL = {"CHUNK": "dokumentum-részlet", "LAB_RESULT": "labor", "STATEMENT": "leletből",
               "MEDICATION": "gyógyszer", "ALLERGY": "allergia"}
# [3]  [2, 5]  [Forrás: 4]
_MARK = re.compile(r"\[\s*(?:forrás\s*:?\s*)?(\d+(?:\s*[,;]\s*\d+)*)\s*\]", re.IGNORECASE)
_LIST_ITEM = re.compile(r"^\s*(?:\d+[.)]|[-•*])\s+")
_MD_HEADING = re.compile(r"^\s*#+\s*")
_WORD = re.compile(r"[a-z0-9]+")
# ha a kérdés ezek egyikét tartalmazza, minden labor részletesen megy (pl. "milyen laborjai vannak?")
_ALL_LABS = ("labor", "vervetel")


# Hurok-egység: sor, ill. soron belül mondat. A tagmondat-szintű figyelés (összefoglaló) itt hamisan
# jelzett: a felsorolás pontjaiban a "magas a referencia felett (2026" rész a dátum pontjainál
# tagmondattá törik, és minden pontban azonos.
_SENTENCE_BREAK = re.compile(r"(?<=[.!?])\s+(?=[A-ZÁÉÍÓÖŐÚÜŰ*])")
_LOOP_MIN_WORDS = 4


def loop_start(text: str) -> int | None:
    """Egy (legalább 4 szavas) sor/mondat szó szerinti ismétlődésének kezdete — a számozás nem
    számít ("8. **Kreatinin**: 80 ..." = "12. **Kreatinin**: 80 ..."). Csak a lezárt egységek
    számítanak (az utolsó, még íródó nem)."""
    seen: set[str] = set()
    pos = 0
    lines = text.split("\n")
    for li, line in enumerate(lines):
        complete_line = li < len(lines) - 1
        start = 0
        units = _SENTENCE_BREAK.split(line)
        for ui, unit in enumerate(units):
            offset = line.find(unit, start)
            start = offset + len(unit)
            if not complete_line and ui == len(units) - 1:
                break  # az íródó egység
            key = _clause_key(_LIST_ITEM.sub("", unit))
            if len(key.split()) >= _LOOP_MIN_WORDS:
                if key in seen:
                    return pos + offset
                seen.add(key)
        pos += len(line) + 1
    return None


def prompt_hash() -> str:
    payload = json.dumps({"kind": "clinical-chat", "system": SYSTEM, "format": FORMAT_REMINDER, "options": _OPTIONS,
                          "maxTokens": MAX_ANSWER_TOKENS, "extractor": config.EXTRACTOR_VERSION},
                         ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def retrieve(retrieve_url: str, question: str, history: list[dict]) -> list[dict]:
    """A kérdéshez legközelebbi dokumentum-darabok a Springtől. Visszautaló kérdésnél ("és a
    vesefunkció?") az előző kérdés is a keresőszövegbe kerül."""
    query = question if not history else history[-1]["question"] + "\n" + question
    vector = embedding.embed([query])[0]
    resp = requests.post(retrieve_url, json={"embedding": vector, "embeddingModel": config.EMBEDDING_MODEL},
                         headers={"X-Internal-Token": config.TOKEN}, timeout=RETRIEVE_TIMEOUT_SEC)
    resp.raise_for_status()
    return resp.json()["chunks"]


def _mentioned(label: str, query_words: set[str]) -> bool:
    """A vizsgálat neve (bármely ≥ 3 betűs szava, ill. annak eleje) szerepel-e a kérdésben."""
    name = label.split(" · ")[0]
    for word in _WORD.findall(fold(name)):
        if len(word) >= 3 and any(q.startswith(word) or (len(q) >= 4 and word.startswith(q)) for q in query_words):
            return True
    return False


def select_facts(facts: list[dict], query: str) -> list[dict]:
    """A normál tartományú laborok közül csak a kérdésben említettek mennek részletesen (a többi
    névvel, háttérként): a 80+ soros laborlista a kis modellnél elnyomta a kérdéshez tartozó részt.
    A tartományon kívüliek, az állítások, gyógyszerek, allergiák mindig mennek."""
    folded = fold(query)
    if any(k in folded for k in _ALL_LABS):
        return facts
    words = set(_WORD.findall(folded))
    out, skipped = [], []
    for f in facts:
        if f["kind"] == "LAB_RESULT" and not f.get("abnormal") and not _mentioned(f["label"], words):
            skipped.append(f["label"].split(" · ")[0])
        else:
            out.append(f)
    if skipped:
        out.append({"kind": "BACKGROUND", "id": None, "label": None, "source": None,
                    "text": "Referencián belüli legutóbbi laborértékek (részletek külön kérdésre): "
                            + ", ".join(skipped)})
    return out


def render_context(facts: list[dict], chunks: list[dict]) -> tuple[str, list[dict]]:
    """A modell kontextusa; a hivatkozható tények sorszámot kapnak (a dokumentum-darabok elöl —
    ezek a kérdéshez a legrelevánsabbak). (szöveg, a sorszámok sorrendjében a tények)"""
    numbered = list(chunks) + [f for f in facts if f.get("source")]
    lines = ["FORRÁSOK:"]
    for i, f in enumerate(numbered, 1):
        text = re.sub(r"\s+", " ", f["text"]).strip()
        lines.append(f"[{i}] ({_KIND_LABEL.get(f['kind'], f['kind'])} – {f['label']}) {text}")
    background = [f["text"] for f in facts if not f.get("source")]
    if background:
        lines += ["", "HÁTTÉR (nem hivatkozható):"] + [f"- {b}" for b in background]
    return "\n".join(lines), numbered


def parse_points(text: str, count: int) -> tuple[list[dict], list[int]]:
    """Pontokra bontás és a [n] jelek feloldása. (pontok {text, list, heading, refs}, érvénytelen sorszámok)"""
    raw_points: list[dict] = []
    current = None
    for raw in text.splitlines():
        line = _MD_HEADING.sub("", raw).strip()
        if not line:
            current = None
            continue
        item = _LIST_ITEM.match(raw)
        if item or current is None:
            current = {"text": raw[item.end():].strip() if item else line, "list": bool(item)}
            raw_points.append(current)
        else:
            current["text"] += " " + line

    points: list[dict] = []
    invalid: list[int] = []
    for p in raw_points:
        refs: list[int] = []
        for m in _MARK.finditer(p["text"]):
            for num in re.split(r"[,;]", m.group(1)):
                n = int(num)
                if 1 <= n <= count:
                    if n not in refs:
                        refs.append(n)
                elif n not in invalid:
                    invalid.append(n)
        clean = _MARK.sub("", p["text"])
        clean = re.sub(r"\s+([.,;:!?])", r"\1", clean)
        clean = re.sub(r"\s{2,}", " ", clean).strip()
        if not clean.strip(".,;: "):
            # csak forrás-jel volt a sorban (pl. a modell külön sorba tette) → az előző ponthoz
            if points:
                points[-1]["refs"] += [r for r in refs if r not in points[-1]["refs"]]
            continue
        points.append({"text": clean, "list": p["list"], "heading": not refs and clean.endswith(":"),
                       "refs": refs})
    return points[:MAX_POINTS], invalid


def answer(question: str, history: list[dict], facts: list[dict], chunks: list[dict]) -> dict:
    """A modellhívás és az ellenőrzés; a Spring callback-jének törzse (DONE)."""
    query = question if not history else history[-1]["question"] + "\n" + question
    context_text, numbered = render_context(select_facts(facts, query), chunks)
    messages = [{"role": "system", "content": SYSTEM}]
    for turn in history:
        messages += [{"role": "user", "content": turn["question"]},
                     {"role": "assistant", "content": turn["answer"]}]
    messages.append({"role": "user", "content": f"{context_text}\n\nKÉRDÉS: {question}\n\n{FORMAT_REMINDER}"})

    text, cut = stream_messages(messages, MAX_ANSWER_TOKENS, _OPTIONS, model=config.CHAT_LLM_MODEL,
                                loop_start=loop_start, think=False)
    points, invalid = parse_points(text, len(numbered))
    if not points:
        raise ValueError("A modell nem adott használható választ")

    warnings: list[str] = []
    if cut:
        warnings.append("A modell ismétlésbe kezdett vagy elérte a hosszkorlátot — a válasz az utolsó teljes "
                        "mondatnál le lett zárva")
    if invalid:
        warnings.append("A modell nem létező forrásra hivatkozott (elhagyva): "
                        + ", ".join(f"[{n}]" for n in invalid))
    known = "\n".join([context_text, question] + [t["question"] + "\n" + t["answer"] for t in history])
    warnings += [f"Ellenőrizetlen szám a válaszban: {n} (nem szerepel a beteg adatai között)"
                 for n in unverified_numbers(" ".join(p["text"] for p in points), known)]

    return {
        "status": "DONE",
        "points": [{"text": p["text"][:2000], "list": p["list"], "heading": p["heading"],
                    "sources": [{"kind": numbered[r - 1]["kind"], "id": numbered[r - 1]["id"]}
                                for r in p["refs"]][:10]}
                   for p in points],
        "warnings": [w[:500] for w in warnings],
        "model": {"modelName": config.CHAT_LLM_MODEL, "seed": config.LLM_SEED, "promptHash": prompt_hash(),
                  "extractorVersion": config.EXTRACTOR_VERSION},
    }
