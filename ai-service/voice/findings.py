"""Csoportosított elemek a felvétel átiratából (POST /recording-findings): panaszok, diagnózisok, beutalók,
vizsgálati megállapítások, javasolt gyógyszerek, javaslatok / kontroll. Az orvos a listát szerkeszti.

A modell (MedGemma, kötött JSON) csak JAVASOL; determinisztikus ellenőrzés:
  - minden elem megszólalásra hivatkozik; ha a hivatkozás hiányzik / nem létezik / nem illik a szöveghez
    (nincs közös szótő), a forrást szótő-egyezéssel keressük (summary.ground). Ha így sincs, az elem kimarad
    (figyelmeztetéssel) — ami nem hangzott el, nem kerül a listába;
  - a tagadott tünet ("nincs láz") nem panasz: kimarad, figyelmeztetéssel;
  - az átiratban nem szereplő szám → az elem "ellenőrizendő" jelölést kap;
  - gyógyszer (név + adag + mértékegység, pl. "metoprolol 50 mg") a Gyógyszerek csoportba kerül: ha a modell
    másik csoportba tette (a 4B a diktált gyógyszerlistát panasznak vette, 2026-11-15), áthelyezzük; ha
    kihagyta (a 27B ugyanarra üres listát adott), az átiratból — szó szerint, a megszólalásra hivatkozva — pótoljuk;
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
# egy diktált gyógyszerlista ennél hosszabb is lehet (a 21-es felvételen 8 szer + 1 leállított + allergia)
MAX_MEDICATIONS = 20
MAX_TEXT = 300

# (JSON-kulcs a modellnek, a Spring kategóriája, leírás a promptban)
CATEGORIES = [
    ("panaszok", "COMPLAINT", "a beteg JELENLEGI panaszai, tünetei (ha elhangzott: kezdet, lokalizáció, jelleg). "
                              "A tagadott tünet (\"nincs láza\") NEM panasz. Gyógyszer, gyógyszerlista, "
                              "allergia SOSEM panasz."),
    ("diagnozisok", "DIAGNOSIS", "diagnózis vagy diagnózis-gyanú, CSAK ha az orvos kimondta (a \"gyanú\" szót tartsd "
                                 "meg). Ne következtess tünetekből."),
    ("beutalok", "REFERRAL", "hova / milyen szakrendelésre, osztályra, vizsgálatra küldi vagy utalja az orvos a beteget."),
    ("vizsgalati_megallapitasok", "EXAM_FINDING", "fizikális vizsgálat és mérések eredménye (vérnyomás, pulzus, "
                                                  "echo-paraméterek stb.), értékkel és mértékegységgel, pontosan úgy, "
                                                  "ahogy elhangzott; a tagadó lelet is (\"has puha, nem érzékeny\")."),
    ("gyogyszerek", "MEDICATION", "MINDEN elhangzott gyógyszer, soronként egy: a jelenleg szedett gyógyszerek "
                                  "(gyógyszerlista), a most felírt, módosított vagy leállított szerek — név, és ha "
                                  "elhangzott: adag, szedés. Leállítottnál írd mögé zárójelben, és ha elhangzott, "
                                  "az okát (pl. \"diklofenak (leállítva gyomorvérzés miatt)\"). "
                                  "Gyógyszer-allergia is ide, pl. \"Allergia: szulfonamid (csalánkiütés)\"."),
    ("javaslatok", "RECOMMENDATION", "egyéb teendő: kontroll (mikor, hol), életmód, diéta, mikor jöjjön vissza; "
                                     "kerülendő gyógyszer vagy gyógyszer-kombináció."),
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
    "echo": "orvosi diktálás (echokardiográfiás lelet, gyógyszerlista, anamnézis stb.; csak az orvos megszólalásai)",
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
[0] ORVOS: Mi a panasza? [1] BETEG: Két napja fáj a hasam jobb oldalt, lázam nincs. Bisoprololt szedek, 5 mg
reggel, a szulfonamidra csalánkiütést kaptam. [2] ORVOS: A has jobb alsó negyede nyomásérzékeny. Appendicitis gyanú,
sebészeti konzílium. Metamizolt adunk, holnap kontroll.
→ {{"panaszok": [{{"szoveg": "két napja fennálló jobb oldali hasi fájdalom", "forras": [1]}}],
"diagnozisok": [{{"szoveg": "appendicitis gyanú", "forras": [2]}}],
"beutalok": [{{"szoveg": "sebészeti konzílium", "forras": [2]}}],
"vizsgalati_megallapitasok": [{{"szoveg": "a has jobb alsó negyede nyomásérzékeny", "forras": [2]}}],
"gyogyszerek": [{{"szoveg": "bisoprolol 5 mg reggel", "forras": [1]}}, {{"szoveg": "Allergia: szulfonamid
(csalánkiütés)", "forras": [1]}}, {{"szoveg": "metamizol", "forras": [2]}}],
"javaslatok": [{{"szoveg": "kontroll holnap", "forras": [2]}}]}}"""

_OPTIONS = {"temperature": 0, "seed": SEED, "repeat_penalty": 1.1, "num_ctx": 8192, "num_predict": 3000}
# A JSON-kimenet hosszabb, mint az összefoglalóé: a 27B (8 GB VRAM-on részben CPU-n) 120 mp alatt nem végzett
# egy 8 megszólalásos próbán (2026-10-23); a 4B ~20–60 mp
DEADLINE = int(os.environ.get("FINDINGS_DEADLINE", str(max(bp.LLM_DEADLINE, 600))))

_NUMBER = re.compile(r"\d+(?:[.,]\d+)?")
# Gyógyszer az átiratban: név + adag + gyógyszer-mértékegység, utána a szedés a tagolójelig ("metoprolol 50 mg
# reggel és este"). A mértékegység szűr: a mérés (Hgmm, /perc, %, mm) nem ilyen; a "ml"/"g" kimarad (folyadék, só).
_DOSE = re.compile(r"(?<![\w])(?P<name>[^\W\d_][\w-]{3,})\s+(?P<dose>\d+(?:[.,]\d+)?)\s?"
                   r"(?P<unit>mg|mcg|µg|μg|ug|NE|IU|tabletta|tbl|csepp|puff)(?![\w])(?P<rest>[^,.;:]{0,80})")
# "nincs ismert allergiája", "allergiát tagad": nem allergia
_NEG_ALLERGY = re.compile(r"(?i)\b(nincs|nincsen|nem ismert|tagad|negatív)")
# Hatóanyag-név a nemzetközi (INN) szóvégekről, magyar raggal is ("ibuprofent", "varfarinnal") — csak a
# panasz / vizsgálati megállapítás átsorolásához, gyógyszert nem ebből veszünk fel
_DRUG_SUFFIX = re.compile(r"(?i)[a-z]{2,}(?:prol|olol|pril|sartan|statin|profen|farin|daron|cillin|mycin|micin|prazol|"
                          r"szemid|semid|formin|gliflozin|gliptin|dipin|xaban|gatran|parin|fenac|fenak|oxacin|"
                          r"cyclin|ciklin|tiazid|lukast|azepam|zepam|setron|tidin|metazon|nizolon|szalicilsav|"
                          r"salicilsav)[a-záéíóöőúüű]{0,5}\b")
_AVOID = re.compile(r"(?i)kerülend|kerülni|kerülje|ne szedje|ne adjuk|kontraindik|ellenjavallt")
# a szám előtt álló, nem gyógyszernév szavak ("napi 50 mg", "összesen 100 mg")
_NOT_DRUG = {"napi", "naponta", "összesen", "maximum", "maximálisan", "legfeljebb", "legalább", "körülbelül",
             "mintegy", "adag", "adagja", "adagban", "dózis", "dózisban", "plusz", "reggel", "este", "délben",
             "után", "előtt", "helyett", "további", "egyszer", "kétszer", "háromszor", "szükség", "esetén"}
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


def drug_mentions(utterances: list[dict]) -> list[dict]:
    """Az átiratban elhangzott gyógyszer + adag, szó szerint ("metoprolol 50 mg reggel és este"), a
    megszólalására hivatkozva."""
    found: list[dict] = []
    for u in utterances:
        for m in _DOSE.finditer(u["text"]):
            name = m.group("name")
            if name.lower() in _NOT_DRUG:
                continue
            rest = " ".join(m.group("rest").split()[:8])
            found.append({"name": name, "text": f"{name} {m.group('dose')} {m.group('unit')} {rest}".strip(),
                          "turnId": u["turnId"]})
    return found


def _drug_stem(word: str) -> str:
    return word.lower()[:5]


def _route(category: str, text: str, drug_stems: set[str]) -> str:
    """Determinisztikus csoport-javítás: gyógyszer és gyógyszer-allergia a Gyógyszerek csoportba, a kerülendő
    gyógyszer a Javaslatok közé. Diagnózist és beutalót nem mozgatunk (a "warfarin-túladagolás" diagnózis)."""
    if category in ("MEDICATION", "RECOMMENDATION", "DIAGNOSIS", "REFERRAL"):
        return category
    first = re.findall(r"[^\W\d_]{4,}", text)
    drug = (_DOSE.search(text) or _DRUG_SUFFIX.search(text)
            or (first and _drug_stem(first[0]) in drug_stems))
    if drug and _AVOID.search(text):
        return "RECOMMENDATION"
    if drug or re.match(r"(?i)allergi", text):
        return "MEDICATION"
    return category


def validate(raw: dict, utterances: list[dict], warnings: list[str]) -> list[dict]:
    by_turn = {u["turnId"]: u for u in utterances}
    transcript = " ".join(u["text"] for u in utterances)
    items: list[dict] = []
    drugs = drug_mentions(utterances)
    drug_stems = {_drug_stem(d["name"]) for d in drugs}
    seen: set[str] = set()
    for key, model_category, _ in CATEGORIES:
        entries = raw.get(key) if isinstance(raw.get(key), list) else []
        for entry in entries:
            if not isinstance(entry, dict) or not isinstance(entry.get("szoveg"), str):
                continue
            text = re.sub(r"\(\s*\)", "", re.sub(r":?\s*<[^<>]*>", "", entry["szoveg"].replace("**", "")))  # sablon-hely
            text = " ".join(text.split()).strip(" -•;:")[:MAX_TEXT]
            if not text or text.lower() in seen:
                continue
            category = _route(model_category, text, drug_stems)
            if category != model_category:
                log.info("Csoport javítva: %s → %s: %s", model_category, category, text[:80])
            limit = MAX_MEDICATIONS if category == "MEDICATION" else MAX_PER_CATEGORY
            if len([i for i in items if i["category"] == category]) >= limit:
                continue
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
    # a modell által kihagyott gyógyszer (név + adag) pótlása az átiratból, szó szerint
    med_text = " ".join(i["text"] for i in items if i["category"] == "MEDICATION").lower()
    med_stems = {_drug_stem(w) for w in re.findall(r"[^\W\d_]{4,}", med_text)}
    for d in drugs:
        if _drug_stem(d["name"]) in med_stems or d["text"].lower() in seen:
            continue
        if len([i for i in items if i["category"] == "MEDICATION"]) >= MAX_MEDICATIONS:
            break
        log.info("Gyógyszer pótolva az átiratból: %s", d["text"])
        seen.add(d["text"].lower())
        med_stems.add(_drug_stem(d["name"]))
        items.append({"category": "MEDICATION", "text": d["text"][:MAX_TEXT], "bnoCode": None,
                      "turnIds": [d["turnId"]], "flag": None})
    # a kihagyott allergia: az "allergi…" mondat szó szerint (a 4B a diktált penicillin-allergiát kihagyta)
    if not any(re.search(r"(?i)allergi", i["text"]) for i in items):
        for u in utterances:
            for sent in re.split(r"(?<=[.!?])\s+", u["text"]):
                if re.search(r"(?i)\ballergi", sent) and not _NEG_ALLERGY.search(sent):
                    text = " ".join(sent.split()).strip(" .")[:MAX_TEXT]
                    log.info("Allergia pótolva az átiratból: %s", text)
                    items.append({"category": "MEDICATION", "text": text, "bnoCode": None,
                                  "turnIds": [u["turnId"]], "flag": None})
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
