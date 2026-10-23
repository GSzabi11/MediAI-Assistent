"""Epikrízis egy ellátáshoz (docs/epicrisis.md): az orvos által kiválasztott leletekből, az ellátás
hangfelvételeinek átiratából és a beteg aktív gyógyszereiből.

Sablon (a fejlécet — Beteg, TAJ, Dátum — a Spring tölti az adatbázisból, nem a modell):
    DIAGNÓZISOK            a leletek kivonatából (history.make_digest) + a modell (az átiratból), idézettel
    KRITIKUS MEGÁLLAPÍTÁSOK  a modell, pontonként forrással (lelet / megszólalás) és szó szerinti idézettel
    JAVASLATOK             a modell, feltételes mód, legfeljebb 6

A dokumentum-kivonatok ugyanazok, mint a kórtörténetnél (közös document_digest tábla): ami már elkészült,
nem számolódik újra.

A modell csak javasol. Determinisztikus ellenőrzés (sosem csendes):
- a megállapítás forrása csak a bemenet egy lelete vagy megszólalása lehet; az idézetnek szó szerint ott
  kell állnia — különben a pont "ellenőrizendő" jelölést kap (az orvos dönt);
- a BNO-kód csak akkor marad, ha valamelyik kiválasztott leletben szó szerint áll;
- a modell diagnózisa csak szó szerinti idézettel marad meg;
- szakmailag szokatlan tartalom (alkoholfogyasztás javaslata) → ellenőrizendő; javaslatból kimarad;
- a forrásban nem szereplő szám → ellenőrizendő; gyógyszeradag-eltérés a listától → figyelmeztetés.
"""
import hashlib
import json
import re

import requests

import config
import history
from summarize import dose_mismatches, unverified_numbers
from textutil import fold

SYSTEM = """Magyar klinikus asszisztens vagy. Egy ellátás (sürgősségi / ambuláns / echo vizsgálat) adataiból —
leletek, a vizsgálat hangfelvételének átirata, aktív gyógyszerek — egy epikrízis két részét készíted el egy
orvosnak.
Mezők:
- "kritikus_megallapitasok": az ellátás legfontosabb, klinikailag jelentős megállapításai, legfeljebb 6 pont:
  miért jelentkezett a beteg (fő panasz), a kóros vizsgálati és laboreredmények, a lelet véleménye, a
  vizsgálat során elhangzott fontos tények. Mindegyik 1–2 tárgyszerű mondat ("szoveg"), a tagadást
  ("nincs", "nem igazolódott") tartsd meg.
  "forras": a pontot alátámasztó azonosítók a bemenetből, pontosan úgy, ahogy ott állnak: lelet "D12",
  megszólalás "H5:3". "idezet": a forrásból SZÓ SZERINT kimásolt rövid rész (legfeljebb 1 mondat).
- "diagnozisok": azok a diagnózisok, amelyek az ÁTIRATBAN hangzanak el (a leletek diagnózisait már ismerjük,
  azokat ne ismételd). "nev": a diagnózis neve szavakkal; "forras" és "idezet" mint fent. BNO-kódot ne adj.
Szabályok:
- KIZÁRÓLAG a megadott adatokat használd. Ne találj ki diagnózist, értéket, dátumot, gyógyszert.
- Számot csak a bemenetből idézz, pontosan.
- A beteget ne nevezd meg ("A beteg …"). A szövegben "[beteg]" jelöli a nevét."""

SYSTEM_RECOMMENDATIONS = """Magyar klinikus asszisztens vagy. Egy ellátás adatai (diagnózisok, kritikus
megállapítások, eltérő laborértékek, aktív gyógyszerek) alapján az epikrízis javaslatait írod: konkrét
következő lépéseket (kontroll-labor, konzílium, vizsgálat, a gyógyszerelés felülvizsgálata — adag,
ellenjavallat, kölcsönhatás —, életmód).
Szabályok:
- Legfeljebb 6 javaslat ("javaslatok"), mindegyik egy rövid mondat.
- Mindegyik egy megadott tényre (megállapításra, laborértékre, diagnózisra, gyógyszerre) épüljön; ha egy
  lelet vagy az orvos már javasolta, azt is felveheted.
- Szakmailag elfogadott, bizonyítékokon alapuló gyakorlatot kövess; feltételes mód ("javasolt",
  "mérlegelendő") — a döntés az orvosé.
- Gyógyszert és adagot csak az "Aktív gyógyszerek" listából említs."""

_STR_LIST = {"type": "array", "items": {"type": "string"}}
SCHEMA = {
    "type": "object",
    "properties": {
        "kritikus_megallapitasok": {
            "type": "array",
            "items": {"type": "object",
                      "properties": {"szoveg": {"type": "string"}, "forras": _STR_LIST, "idezet": {"type": "string"}},
                      "required": ["szoveg", "forras", "idezet"]},
        },
        "diagnozisok": {
            "type": "array",
            "items": {"type": "object",
                      "properties": {"nev": {"type": "string"}, "forras": _STR_LIST, "idezet": {"type": "string"}},
                      "required": ["nev", "forras", "idezet"]},
        },
    },
    "required": ["kritikus_megallapitasok", "diagnozisok"],
}
SCHEMA_RECOMMENDATIONS = {
    "type": "object",
    "properties": {"javaslatok": _STR_LIST},
    "required": ["javaslatok"],
}

_OPTIONS = {"temperature": 0, "repeat_penalty": 1.2, "repeat_last_n": 256}
MAX_FINDINGS = 6
MAX_RECOMMENDATIONS = 6
# a lelet szövegéből ennyi megy a modellhez (a kivonat mellett — az idézet ebből jöhet); összesen legfeljebb
DOC_TEXT_MAX = 4000
DOC_TEXT_TOTAL = 16000

_SOURCE = re.compile(r"^\s*\[?\s*(?:(D)\s*(\d+)|(H)\s*(\d+)\s*[:/.-]\s*(\d+))\s*]?\s*$", re.IGNORECASE)
_SPEAKERS = {"DOCTOR": "Orvos", "PATIENT": "Beteg", "OTHER": "Egyéb"}
_CARE_TYPES = {"SBO": "sürgősségi (SBO) ellátás", "AMBULANCE": "ambuláns ellátás", "ECHO": "echokardiográfia"}
_KINDS = {"AMBULANCE": "ambuláns lap", "IMAGING": "képalkotó lelet"}


def _hash(payload: dict) -> str:
    return hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()


def prompt_hash() -> str:
    return _hash({"kind": "epicrisis", "system": SYSTEM, "schema": SCHEMA, "recommendations": SYSTEM_RECOMMENDATIONS,
                  "options": _OPTIONS, "model": config.HISTORY_LLM_MODEL, "digest": history.digest_prompt_hash()})


def _model() -> dict:
    return {"modelName": config.HISTORY_LLM_MODEL, "seed": config.LLM_SEED, "promptHash": prompt_hash(),
            "extractorVersion": config.EXTRACTOR_VERSION}


def _chat_json(system: str, user: str, schema: dict, max_tokens: int) -> dict:
    resp = requests.post(
        f"{config.OLLAMA_URL}/api/chat",
        json={"model": config.HISTORY_LLM_MODEL,
              "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
              "format": schema, "stream": False,
              "options": {**_OPTIONS, "seed": config.LLM_SEED, "num_ctx": config.LLM_NUM_CTX,
                          "num_predict": max_tokens}},
        timeout=config.LLM_TIMEOUT_SEC,
    )
    if resp.status_code >= 400:
        # az Ollama hibaüzenete (pl. kevés memória a modellhez) a válasz törzsében van — ne vesszen el
        raise ValueError(f"Ollama-hiba (HTTP {resp.status_code}): {resp.text[:500]}")
    body = resp.json()
    if "error" in body:
        raise ValueError(f"Ollama-hiba: {body['error']}")
    if body.get("done_reason") == "length":
        raise ValueError("A modell válasza elérte a hosszkorlátot, nem teljes")
    try:
        data = json.loads((body.get("message") or {}).get("content", ""))
    except json.JSONDecodeError as e:
        raise ValueError(f"A modell kimenete nem érvényes JSON: {e}") from e
    if not isinstance(data, dict):
        raise ValueError("A modell kimenete nem JSON-objektum")
    return data


def _clean(s, limit: int) -> str | None:
    if not isinstance(s, str):
        return None
    s = " ".join(s.split())
    return s[:limit] if s else None


# ---------------------------------------------------------------- bemenet


class Sources:
    """A modellnek átadott források szövege azonosító szerint ("D12", "H5:3") — az idézet- és
    számellenőrzés ezekhez mér."""

    def __init__(self, documents: list[dict], labs: list[dict], recordings: list[dict]):
        self.documents = {d["id"]: d["text"] for d in documents}
        self.labs = {lab["documentId"]: "\n".join(lab.get("abnormal") or []) for lab in labs}
        self.utterances = {(r["id"], u["turnId"]): u["text"] for r in recordings for u in r.get("utterances") or []}

    def parse(self, raw) -> tuple[list[int], int | None, list[int]]:
        """(dokumentum-azonosítók, felvétel-azonosító, megszólalások) — csak a bemenetben létezők. Egy pont
        egy felvételre hivatkozhat (a Spring így tárolja); a többi felvétel hivatkozása kimarad."""
        docs, recording, turns = [], None, []
        for item in raw if isinstance(raw, list) else [raw]:
            m = _SOURCE.match(str(item or ""))
            if not m:
                continue
            if m.group(1):
                doc = int(m.group(2))
                if (doc in self.documents or doc in self.labs) and doc not in docs:
                    docs.append(doc)
            else:
                rec, turn = int(m.group(4)), int(m.group(5))
                if (rec, turn) in self.utterances and recording in (None, rec) and turn not in turns:
                    recording = rec
                    turns.append(turn)
        return docs, recording, sorted(turns)

    def text(self, docs: list[int], recording: int | None, turns: list[int]) -> str:
        parts = [self.documents.get(d) or self.labs.get(d) or "" for d in docs]
        parts += [self.utterances[(recording, t)] for t in turns]
        return "\n".join(parts)


def render_input(facts: dict, documents: list[dict], digests: dict[int, dict]) -> str:
    lines = []
    care = facts.get("care") or {}
    period = str(care.get("start")) + (f" – {care['end']}" if care.get("end") else " (nyitott)")
    lines.append(f"Ellátás: {_CARE_TYPES.get(care.get('type'), 'ellátás')}, {period}")
    if facts.get("ageYears") is not None:
        lines.append(f"Életkor: {facts['ageYears']} év")

    budget = DOC_TEXT_TOTAL
    if documents:
        lines.append("\nLeletek:")
    for doc in documents:
        dg = digests[doc["id"]]
        place = f" ({dg['place']})" if dg.get("place") else ""
        lines.append(f"[D{doc['id']}] {doc.get('date')} {_KINDS.get(doc.get('type'), 'lelet')} — {dg['title']}{place}")
        lines.append(f"  Kivonat: {dg['summary']}")
        if dg.get("diagnoses"):
            lines.append("  Diagnózisok: " + "; ".join(d["name"] + (f" ({d['bnoCode']})" if d.get("bnoCode") else "")
                                                      for d in dg["diagnoses"]))
        text = doc["text"][:min(DOC_TEXT_MAX, max(budget, 0))]
        budget -= len(text)
        if text:
            lines.append("  Szöveg:\n" + "\n".join("    " + line for line in text.splitlines() if line.strip()))
    labs = facts.get("labs") or []
    if labs:
        lines.append("\nLaborleletek (a szabálymotor szerinti eltérések):")
        for lab in labs:
            abnormal = lab.get("abnormal") or []
            body = "; ".join(abnormal) if abnormal else f"{lab.get('total')} vizsgálat, mind referenciatartományon belül"
            lines.append(f"[D{lab['documentId']}] {lab['date']}: {body}")
    for r in facts.get("recordings") or []:
        lines.append(f"\nHangfelvétel átirata (#{r['id']}, {r.get('recordedAt')}, "
                     f"{'echo-diktálás' if r.get('mode') == 'ECHO' else 'orvos–beteg beszélgetés'}):")
        for u in r.get("utterances") or []:
            lines.append(f"[H{r['id']}:{u['turnId']}] {_SPEAKERS.get(u.get('speaker'), 'Egyéb')}: {u['text']}")
        if r.get("truncated"):
            lines.append("(az átirat vége terjedelmi okból kimaradt)")
    lines.append("\n" + render_medications(facts))
    return "\n".join(lines)


def render_medications(facts: dict) -> str:
    meds = facts.get("medications") or []
    lines = ["Aktív gyógyszerek:" if meds else "Aktív gyógyszerek: nincs rögzítve"]
    for m in meds:
        parts = [m["name"]] + [p for p in (m.get("activeIngredient"), m.get("dose"), m.get("route")) if p]
        lines.append("- " + ", ".join(parts) + ("" if m.get("verified") else " (nem ellenőrzött)"))
    allergies = facts.get("allergies") or []
    if allergies:
        lines.append("Allergiák: " + ", ".join(a["substance"] for a in allergies))
    return "\n".join(lines)


# ---------------------------------------------------------------- ellenőrzés


def check_finding(item: dict, sources: Sources) -> dict | None:
    text = history.unmask(_clean(item.get("szoveg"), 1000))
    if not text:
        return None
    quote = _clean(item.get("idezet"), 1000)
    docs, recording, turns = sources.parse(item.get("forras"))
    source_text = sources.text(docs, recording, turns)
    flag = None
    if not docs and recording is None:
        flag = "Nincs érvényes forrás-hivatkozás"
        quote = None
    elif not quote or not history.quote_in_text(quote, source_text):
        flag = "Nem támasztható alá szó szerinti idézettel a forrásból"
        quote = None
    if s := history.suspicious(text) or history.suspicious(quote):
        flag = s
    elif not flag and (numbers := unverified_numbers(text, source_text)):
        flag = "A forrásban nem szereplő szám: " + ", ".join(numbers)
    return {"text": text, "documentId": docs[0] if docs else None, "recordingId": recording,
            "turnIds": turns, "quote": quote, "flag": flag}


def model_diagnoses(raw: list, sources: Sources, documents: list[dict], known: list[dict],
                    warnings: list[str]) -> list[dict]:
    """A modell diagnózisai (az átiratból): csak szó szerinti idézettel; BNO-kód nem jön a modelltől."""
    out = []
    names = {fold(d["name"]) for d in known}
    for d in raw or []:
        name = history.unmask(_clean(d.get("nev"), 300))
        quote = _clean(d.get("idezet"), 1000)
        if not name or fold(name) in names:
            continue
        docs, recording, turns = sources.parse(d.get("forras"))
        if (not docs and recording is None) or not quote or not history.quote_in_text(
                quote, sources.text(docs, recording, turns)):
            warnings.append(f"Diagnózis ({name}) nem támasztható alá idézettel — kimaradt")
            continue
        if history.suspicious(name):
            continue
        names.add(fold(name))
        out.append({"name": name, "bnoCode": None, "documentId": docs[0] if docs else None,
                    "recordingId": recording, "turnIds": turns})
    return out


# ---------------------------------------------------------------- folyamat


def build(facts: dict, progress=None) -> dict:
    """A Spring callback-jének törzse (DONE). progress(üzenet, lépés, összes): a felület haladásjelzője."""
    report = progress or (lambda message, step, total: None)
    warnings: list[str] = []
    documents = [d for d in facts.get("documents") or [] if d.get("text")]
    recordings = [r for r in facts.get("recordings") or [] if r.get("utterances")]
    labs = facts.get("labs") or []
    if not documents and not recordings and not labs:
        raise ValueError("Nincs lelet és átirat, amiből epikrízis készülhetne")

    # 1. dokumentum-kivonatok (közösek a kórtörténettel)
    current = history.digest_prompt_hash()
    missing = [d for d in documents if not (d.get("digest") and d["digest"].get("promptHash") == current)]
    total = len(missing) + 2
    digests: dict[int, dict] = {}
    new_digests: list[dict] = []
    for doc in documents:
        old = doc.get("digest")
        if old and old.get("promptHash") == current:
            digests[doc["id"]] = old
            continue
        step = missing.index(doc) + 1
        report(f"Dokumentum-kivonat {step}/{len(missing)}: {doc.get('date')} {_KINDS.get(doc.get('type'), 'lelet')}",
               step, total)
        try:
            dg = history.make_digest(doc)
        except (ValueError, requests.RequestException) as e:
            warnings.append(f"A(z) {doc.get('date')} lelet kivonata nem készült el ({e}) — a lelet kimaradt"[:500])
            continue
        digests[doc["id"]] = dg
        new_digests.append({k: v for k, v in dg.items() if k != "promptHash"})
    documents = [d for d in documents if d["id"] in digests]
    for doc in documents:
        warnings += [f"{doc['date']} {digests[doc['id']]['title']}: {w}" for w in digests[doc["id"]].get("warnings") or []]

    # 2. diagnózisok a leletekből (determinisztikus összefésülés; BNO csak a leletből)
    diagnoses = [{"name": d["name"], "bnoCode": d.get("bnoCode"), "documentId": d["documentId"],
                  "recordingId": None, "turnIds": []}
                 for d in history.build_diagnoses(documents, digests)]

    # 3. kritikus megállapítások + az átirat diagnózisai
    sources = Sources(documents, labs, recordings)
    input_text = render_input(facts, documents, digests)
    report("Kritikus megállapítások és diagnózisok", total - 1, total)
    data = _chat_json(SYSTEM, "Az ellátás adatai:\n\n" + input_text, SCHEMA, 2000)
    findings = []
    for item in (data.get("kritikus_megallapitasok") or [])[:MAX_FINDINGS * 2]:
        f = check_finding(item, sources) if isinstance(item, dict) else None
        if f and fold(f["text"]) not in {fold(x["text"]) for x in findings}:
            findings.append(f)
    findings = findings[:MAX_FINDINGS]
    if not findings:
        raise ValueError("A modell nem adott használható kritikus megállapítást")
    diagnoses += model_diagnoses(data.get("diagnozisok"), sources, documents, diagnoses, warnings)
    flagged = [f for f in findings if f["flag"]]
    if flagged:
        warnings.append(f"{len(flagged)} megállapítás ellenőrizendő (a javaslatok nem épülnek rájuk)")

    # 4. javaslatok — az ellenőrizendő megállapítás nem megy a bemenetbe
    rec_input = "\n".join(
        ["Diagnózisok:"] + [f"- {d['name']}" + (f" ({d['bnoCode']})" if d.get("bnoCode") else "") for d in diagnoses]
        + ["\nKritikus megállapítások:"] + [f"- {f['text']}" for f in findings if not f["flag"]]
        + ["\nEltérő laborértékek:"] + [f"- {lab['date']}: {a}" for lab in labs for a in lab.get("abnormal") or []]
        + ["\n" + render_medications(facts)])
    report("Javaslatok", total, total)
    recs = _chat_json(SYSTEM_RECOMMENDATIONS, "Az ellátás adatai:\n\n" + rec_input, SCHEMA_RECOMMENDATIONS, 800)
    recommendations: list[str] = []
    for r in recs.get("javaslatok") or []:
        r = history.unmask(_clean(r, 500))
        if not r or len(r) < 4:
            continue
        if history.suspicious(r):
            warnings.append(f"Szakmailag szokatlan javaslat kimaradt: {r}"[:500])
        elif fold(r) not in {fold(x) for x in recommendations}:
            recommendations.append(r)
    recommendations = recommendations[:MAX_RECOMMENDATIONS]
    rec_text = " ".join(recommendations)
    warnings += dose_mismatches(rec_text, facts)
    warnings += [f"Ellenőrizetlen szám a javaslatokban: {n} (nem szerepel az ellátás adatai között)"
                 for n in unverified_numbers(rec_text, input_text)]
    if any(r.get("truncated") for r in recordings):
        warnings.append("Egy hangfelvétel átiratának vége terjedelmi okból nem került a modellhez")

    return {
        "status": "DONE",
        "diagnoses": diagnoses,
        "findings": findings,
        "recommendations": recommendations,
        "warnings": [w[:500] for w in warnings],
        "digests": new_digests,
        "model": _model(),
    }
