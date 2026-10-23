"""Csoportosított elemek a felvétel átiratából (POST /recording-findings): panaszok, diagnózisok, beutalók,
vizsgálati megállapítások, javasolt gyógyszerek, javaslatok / kontroll. Az orvos a listát szerkeszti.

A modell (MedGemma, kötött JSON) csak JAVASOL; determinisztikus ellenőrzés:
  - minden elem megszólalásra hivatkozik; ha a hivatkozás hiányzik / nem létezik / nem illik a szöveghez
    (nincs közös szótő), a forrást szótő-egyezéssel keressük (summary.ground). Ha így sincs, az elem kimarad
    (figyelmeztetéssel) — ami nem hangzott el, nem kerül a listába;
  - a tagadott tünet ("nincs láz") nem panasz: kimarad, figyelmeztetéssel;
  - az átiratban nem szereplő szám → az elem "ellenőrizendő" jelölést kap;
  - BNO-kódot a modell nem ad. Panasznál / diagnózisnál csak akkor kerül kód az elemre, ha a hivatkozott
    megszólalásban a "BNO" szó mellett szó szerint elhangzott (pl. diktálásnál); különben az orvos írja be.
Echo-diktálásnál csak az orvos megszólalásai (mint az összefoglalónál — a felhasználó döntése, 2026-10-15).
"""
import hashlib
import json
import logging
import os
import re

import requests

import beszed_pipeline as bp
import review
import summary

log = logging.getLogger("voice.findings")

SEED = 42
MAX_PER_CATEGORY = 8
MAX_TEXT = 300

# (JSON-kulcs a modellnek, a Spring kategóriája, leírás a promptban)
CATEGORIES = [
    ("panaszok", "COMPLAINT", "a beteg JELENLEGI panaszai, tünetei (ha elhangzott: kezdet, lokalizáció, jelleg). "
                              "A tagadott tünet (\"nincs láza\") NEM panasz."),
    ("diagnozisok", "DIAGNOSIS", "diagnózis vagy diagnózis-gyanú, CSAK ha az orvos kimondta (a \"gyanú\" szót tartsd "
                                 "meg). Ne következtess tünetekből."),
    ("beutalok", "REFERRAL", "hova / milyen szakrendelésre, osztályra, vizsgálatra küldi vagy utalja az orvos a beteget."),
    ("vizsgalati_megallapitasok", "EXAM_FINDING", "fizikális vizsgálat és mérések eredménye (vérnyomás, pulzus, "
                                                  "echo-paraméterek stb.), értékkel és mértékegységgel, pontosan úgy, "
                                                  "ahogy elhangzott; a tagadó lelet is (\"has puha, nem érzékeny\")."),
    ("javasolt_gyogyszerek", "MEDICATION", "gyógyszer, amit az orvos MOST javasol, felír vagy módosít (név, és ha "
                                           "elhangzott: adag, szedés). A beteg által eddig szedett gyógyszer NEM ide."),
    ("javaslatok", "RECOMMENDATION", "egyéb teendő: kontroll (mikor, hol), életmód, diéta, mikor jöjjön vissza."),
]
CATEGORY_OF = {key: cat for key, cat, _ in CATEGORIES}

_ITEM = {"type": "object",
         "properties": {"szoveg": {"type": "string"}, "forras": {"type": "array", "items": {"type": "integer"}}},
         "required": ["szoveg", "forras"]}
SCHEMA = {"type": "object",
          "properties": {key: {"type": "array", "items": _ITEM} for key, _, _ in CATEGORIES},
          "required": [key for key, _, _ in CATEGORIES]}

PROMPT = {
    "sbo": "sürgősségi osztályos orvos–beteg beszélgetés",
    "echo": "echokardiográfiás vizsgálat diktálásának (csak az orvos megszólalásai)",
}

RULES = """A {what} átiratát kapod, sorszámozott megszólalásokkal. Gyűjtsd ki belőle csoportonként az elemeket egy
orvosnak:
{groups}
SZABÁLYOK:
- KIZÁRÓLAG az átiratban elhangzottakat használd. Ne találj ki diagnózist, gyógyszert, értéket, kontrollt.
- Egy elem egy rövid, tárgyszerű kifejezés vagy mondat ("szoveg"), magyarul. Számot csak pontosan úgy írj, ahogy
  elhangzott. BNO-kódot ne írj.
- "forras": azoknak a megszólalásoknak a sorszáma, amelyekből az elem származik — az átirat sorainak elején álló
  szám.
- Ha egy csoportba nem hangzott el semmi, az üres lista. Csoportonként legfeljebb {n} elem, ismétlés nélkül.
PÉLDA (más betegről, ne ezeket add vissza):
[0] ORVOS: Mi a panasza? [1] BETEG: Két napja fáj a hasam jobb oldalt, lázam nincs. [2] ORVOS: A has jobb alsó
negyede nyomásérzékeny. Appendicitis gyanú, sebészeti konzílium. Metamizolt adunk, holnap kontroll.
→ {{"panaszok": [{{"szoveg": "két napja fennálló jobb oldali hasi fájdalom", "forras": [1]}}],
"diagnozisok": [{{"szoveg": "appendicitis gyanú", "forras": [2]}}],
"beutalok": [{{"szoveg": "sebészeti konzílium", "forras": [2]}}],
"vizsgalati_megallapitasok": [{{"szoveg": "a has jobb alsó negyede nyomásérzékeny", "forras": [2]}}],
"javasolt_gyogyszerek": [{{"szoveg": "metamizol", "forras": [2]}}],
"javaslatok": [{{"szoveg": "kontroll holnap", "forras": [2]}}]}}"""

_OPTIONS = {"temperature": 0, "seed": SEED, "repeat_penalty": 1.1, "num_ctx": 8192, "num_predict": 1500}
# A JSON-kimenet hosszabb, mint az összefoglalóé: a 27B (8 GB VRAM-on részben CPU-n) 120 mp alatt nem végzett
# egy 8 megszólalásos próbán (2026-10-23); a 4B ~20–60 mp
DEADLINE = int(os.environ.get("FINDINGS_DEADLINE", str(max(bp.LLM_DEADLINE, 600))))

_NUMBER = re.compile(r"\d+(?:[.,]\d+)?")
# "nem" nem: "nem kap levegőt" valódi panasz
_NEGATED = re.compile(r"^(nincs|nincsen|tagad|negatív)", re.IGNORECASE)
# A "BNO" szó mellett elhangzott kód: R07.4, R 07.4, R07,4, I10
_BNO_WORD = re.compile(r"\bBNO\b", re.IGNORECASE)
_BNO_CODE = re.compile(r"(?<![A-Za-z0-9])([A-Z])\s?(\d{2})(?:\s?[.,]\s?([0-9A-Z]{1,4}))?(?![0-9])")


def prompt_hash(mode: str) -> str:
    payload = json.dumps({"kind": "recording-findings", "what": PROMPT[mode], "rules": RULES, "categories": CATEGORIES,
                          "schema": SCHEMA, "options": _OPTIONS, "doctorOnly": mode in summary.DOCTOR_ONLY_MODES,
                          "model": review.REVIEW_MODEL}, ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _generate(content: str) -> dict:
    resp = requests.post(bp.OLLAMA_URL, json={
        "model": review.REVIEW_MODEL, "stream": False, "keep_alive": 0, "options": _OPTIONS, "format": SCHEMA,
        "messages": [{"role": "user", "content": content}]}, timeout=(10, DEADLINE))
    resp.raise_for_status()
    data = resp.json()
    if "error" in data:
        raise RuntimeError(f"Ollama hiba: {data['error']}")
    if data.get("done_reason") == "length":
        raise RuntimeError("a modell válasza elérte a hosszkorlátot, nem teljes")
    try:
        out = json.loads(data.get("message", {}).get("content", ""))
    except json.JSONDecodeError as e:
        raise RuntimeError(f"a modell kimenete nem érvényes JSON: {e}") from e
    if not isinstance(out, dict):
        raise RuntimeError("a modell kimenete nem JSON-objektum")
    return out


def bno_from(utterances: list[dict]) -> str | None:
    """Kód a "BNO" szó mellett elhangzott alakból; csak ha pontosan egy különböző kód áll ott."""
    codes = set()
    for u in utterances:
        if not _BNO_WORD.search(u["text"]):
            continue
        for m in _BNO_CODE.finditer(u["text"]):
            letter, digits, rest = m.groups()
            codes.add(letter + digits + ("." + rest if rest else ""))
    return codes.pop() if len(codes) == 1 else None


def validate(raw: dict, utterances: list[dict], warnings: list[str]) -> list[dict]:
    by_turn = {u["turnId"]: u for u in utterances}
    transcript = " ".join(u["text"] for u in utterances)
    items: list[dict] = []
    for key, category, _ in CATEGORIES:
        entries = raw.get(key) if isinstance(raw.get(key), list) else []
        seen: set[str] = set()
        for entry in entries:
            if not isinstance(entry, dict) or not isinstance(entry.get("szoveg"), str):
                continue
            text = " ".join(entry["szoveg"].replace("**", "").split()).strip(" -•;")[:MAX_TEXT]
            if not text or text.lower() in seen:
                continue
            if len([i for i in items if i["category"] == category]) >= MAX_PER_CATEGORY:
                break
            if category == "COMPLAINT" and _NEGATED.match(text):
                warnings.append(f"Panasz kimaradt (tagadott tünet): „{text[:80]}”")
                continue
            refs = entry.get("forras") if isinstance(entry.get("forras"), list) else []
            refs = sorted({r for r in refs if isinstance(r, int) and r in by_turn})
            stems = summary._stems(text)
            if refs and not stems & set().union(*(summary._stems(by_turn[r]["text"]) for r in refs)):
                refs = []  # a hivatkozott megszólalásban nincs közös szótő: rossz sorszám
            if not refs:
                refs = summary.ground(text, utterances)
            if not refs:
                warnings.append(f"Kimaradt ({key}): „{text[:80]}” — nem köthető megszólaláshoz")
                continue
            flags = []
            for n in _NUMBER.findall(text):
                if n not in transcript and n.replace(",", ".") not in transcript and n.replace(".", ",") not in transcript:
                    flags.append(f"a(z) {n} szám nem szerepel az átiratban")
            bno = bno_from([by_turn[r] for r in refs]) if category in ("COMPLAINT", "DIAGNOSIS") else None
            seen.add(text.lower())
            items.append({
                "category": category,
                "text": text,
                "bnoCode": bno,
                "turnIds": refs[:20],
                "flag": ("Ellenőrizendő: " + "; ".join(flags))[:500] if flags else None,
            })
    return items


def extract(mode: str, utterances: list[dict]) -> dict:
    mode = mode.lower()
    warnings: list[str] = []
    if mode in summary.DOCTOR_ONLY_MODES:
        doctor = [u for u in utterances if u.get("speaker") == "DOCTOR"]
        if not doctor:
            raise RuntimeError("echo-diktálás: az átiratban nincs orvosi megszólalás")
        if len(doctor) < len(utterances):
            warnings.append(f"Echo-diktálás: {len(utterances) - len(doctor)} nem orvosi megszólalás kimaradt")
        utterances = doctor
    dialog = "\n".join(f"[{u['turnId']}] {summary.SPEAKER_LABEL.get(u['speaker'], 'EGYÉB')}: {u['text']}"
                       for u in utterances)
    groups = "\n".join(f'- "{key}": {desc}' for key, _, desc in CATEGORIES)
    content = (RULES.format(what=PROMPT[mode], groups=groups, n=MAX_PER_CATEGORY)
               + "\n\n--- ÁTIRAT ---\n" + dialog + "\n--- ÁTIRAT VÉGE ---\nCsak a JSON objektumot add vissza.")
    raw = _generate(content)
    items = validate(raw, utterances, warnings)
    log.info("Csoportok: %d elem, %d figyelmeztetés", len(items), len(warnings))
    return {
        "status": "DONE",
        "items": items[:100],
        "warnings": [w[:500] for w in warnings][:50],
        "model": {"modelName": review.REVIEW_MODEL, "seed": SEED, "promptHash": prompt_hash(mode)},
    }
