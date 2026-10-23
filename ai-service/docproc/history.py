"""Kórtörténet (Medical history): anamnézis + kórtörténeti összefoglaló, idővonal, diagnózisok, javaslatok
a beteg TELJES előzményéből (docs/medical-history.md).

Két lépés, hogy a modell kontextusa ne teljen meg a leletek szövegével:
1. Dokumentum-kivonat (digest): minden nem labor-dokumentumból EGYSZER (a Spring tárolja; promptváltáskor
   újraszámoljuk) — cím, hely, 1–2 mondat, alátámasztó idézet, korábbi események, diagnózisok.
2. Összefoglaló + javaslatok a kivonatokból, a laborleletek szabálymotor szerinti eltéréseiből, a
   gyógyszer- és allergialistából.

A modell csak javasol. Determinisztikus ellenőrzés (sosem csendes — figyelmeztetés / jelölés):
- az idővonal-elem összefoglalóját szó szerinti idézetnek kell alátámasztania (különben "ellenőrizendő");
- a korábbi esemény és a diagnózis csak idézettel marad meg; a BNO-kód csak akkor, ha a leletben áll;
- szakmailag szokatlan javaslat (pl. alkoholfogyasztás) → ellenőrizendő jelölés, az összefoglalóba nem megy;
- a hely (intézmény/osztály) csak akkor, ha szó szerint a leletben áll;
- ellenőrizetlen szám, gyógyszeradag-eltérés (summarize.py ellenőrzései).
"""
import hashlib
import json
import re

import requests

import config
from summarize import (_BULLET, _sentence_loop_start, _loop_start, dedupe_sentences,
                       dose_mismatches, stream_messages, unverified_numbers)
from textutil import contains_phrase, fold

DIGEST_SYSTEM = """Magyar orvosi dokumentumból (ambuláns lap, zárójelentés, konzílium, képalkotó lelet) rövid
kivonatot készítesz egy beteg kórtörténetéhez. Csak azt írd, ami a dokumentumban szerepel.
Mezők:
- "cim": a vizsgálat vagy ellátás megnevezése 2–5 szóban, a dokumentum saját szavaival (a szakterület vagy a
  vizsgálat neve, ahogy a fejlécben vagy a címben áll, + "konzílium" / "vizsgálat" / "ambuláns ellátás").
- "hely": az intézmény és/vagy osztály neve PONTOSAN úgy, ahogy a dokumentumban áll; ha nincs, null.
- "osszefoglalo": 1–2 tárgyszerű mondat: miért jelentkezett, mit találtak, mi a diagnózis, a vélemény és a
  javaslat. A tagadást ("nincs", "nem igazolódott") tartsd meg.
- "idezet": a dokumentumból SZÓ SZERINT kimásolt, összefüggő rész (legfeljebb 2 mondat), amely az
  összefoglalót leginkább alátámasztja (pl. a vélemény vagy a diagnózis sora). Ne fogalmazd át.
- "korabbi_esemenyek": az anamnézisben említett KORÁBBI események, amelyekhez év vagy dátum tartozik
  (pl. "2010 óta ismert X betegség" → {"datum": "2010", "cim": "X betegség diagnózisa", "idezet": "2010 óta ismert X betegség"}).
  "datum": ÉÉÉÉ vagy ÉÉÉÉ-HH-NN. Dátum nélküli eseményt ne adj vissza. "idezet": szó szerint a dokumentumból.
- "diagnozisok": a dokumentum diagnózisai. "nev": a diagnózis neve szavakkal (nem a kódja); "bno": a BNO-kód, CSAK ha a
  dokumentumban szó szerint szerepel, különben null — kódot SOHA ne találj ki; "kezdet": ha a dokumentum
  megadja, mióta áll fenn (ÉÉÉÉ vagy ÉÉÉÉ-HH-NN), különben null; "idezet": a diagnózis sora szó szerint.
- A beteg nevét, TAJ-számát, lakcímét ne írd le (a szövegben "[beteg]" jelöli)."""

_NULLABLE = {"type": ["string", "null"]}
DIGEST_SCHEMA = {
    "type": "object",
    "properties": {
        "cim": {"type": "string"},
        "hely": _NULLABLE,
        "osszefoglalo": {"type": "string"},
        "idezet": _NULLABLE,
        "korabbi_esemenyek": {
            "type": "array",
            "items": {"type": "object",
                      "properties": {"datum": {"type": "string"}, "cim": {"type": "string"},
                                     "idezet": {"type": "string"}},
                      "required": ["datum", "cim", "idezet"]},
        },
        "diagnozisok": {
            "type": "array",
            "items": {"type": "object",
                      "properties": {"nev": {"type": "string"}, "bno": _NULLABLE, "kezdet": _NULLABLE,
                                     "idezet": {"type": "string"}},
                      "required": ["nev", "bno", "kezdet", "idezet"]},
        },
    },
    "required": ["cim", "hely", "osszefoglalo", "idezet", "korabbi_esemenyek", "diagnozisok"],
}

SYSTEM_HISTORY = """Magyar klinikus asszisztens vagy. Egy beteg teljes kórtörténetének tényeiből (idővonal,
diagnózisok, gyógyszerek) anamnézist és kórtörténeti összefoglalót írsz egy orvosnak.
Szabályok:
- KIZÁRÓLAG a megadott tényeket használd. Ne találj ki diagnózist, értéket, dátumot, gyógyszert.
- Számot (értéket, dátumot, évet) csak a tényekből idézz, pontosan.
- A beteget ne nevezd meg ("A beteg …").
- 3 bekezdés, folyó szöveg, felsorolás nélkül (bekezdések között üres sor):
  1. az alapbetegségek és mióta állnak fenn, a fő panaszok;
  2. a betegség lefolyása időrendben: a fontos vizsgálatok, eredmények, konzíliumok dátummal;
  3. a jelenlegi állapot és a gyógyszerelés (az "Aktív gyógyszerek" listából), a dokumentumokban szereplő
     terápiás javaslatok.
- Minden mondat új információt hozzon; ugyanazt ne ismételd.
- Csak az összefoglaló szövegét írd, cím nélkül."""

SYSTEM_RECOMMENDATIONS = """Magyar klinikus asszisztens vagy. Egy beteg kórtörténetének tényei alapján javaslatokat
adsz egy orvosnak: konkrét következő lépéseket (kontroll-labor, konzílium, vizsgálat, terápia felülvizsgálata,
életmód, diéta).
Szabályok:
- Legfeljebb 6 javaslat, mindegyik külön sorban, "- " kezdettel, egy mondatban.
- Mindegyik egy megadott tényre (eltérő laborértékre, diagnózisra, gyógyszerre) épüljön; ha egy dokumentum
  már javasolta, azt is felveheted.
- Szakmailag elfogadott, a bizonyítékokon alapuló gyakorlatot kövesd; feltételes mód ("javasolt",
  "mérlegelendő") — a döntés az orvosé.
- Csak a felsorolást írd, bevezetés nélkül."""

_OPTIONS = {"temperature": 0, "repeat_penalty": 1.2, "repeat_last_n": 256}
# a kivonat utófeldolgozásának (ellenőrzéseinek) verziója — változáskor a tárolt kivonatok újraszámolódnak
DIGEST_VERSION = 2
DIGEST_MAX_TOKENS = 1500
MAX_RECOMMENDATIONS = 6
LAB_TEXT_MAX = 6  # laborleletenként ennyi eltérés kerül az idővonal szövegébe

_DATE = re.compile(r"^\d{4}(-\d{2}-\d{2})?$")
_BNO = re.compile(r"^[A-Z]\d{2}(\.?[0-9A-Z]{1,2})?$")
_TITLES = {"AMBULANCE": "Ambuláns ellátás", "IMAGING": "Képalkotó vizsgálat"}

# Szakmailag szokatlan, ellenőrizendő tartalom: alkoholfogyasztás javaslata (a minta idővonalán:
# "javasolták, hogy igyon két sört"). Determinisztikus szólista, nem modell — a jelölés nem ítélet, csak
# figyelemfelhívás; a döntés az orvosé.
_ALCOHOL = re.compile(r"\b(sör\w*|bor|bort|borból|pálink\w*|vodk\w*|whisk\w*|rum\w*|konyak\w*|alkohol\w*)\b",
                      re.IGNORECASE)
_ADVICE = re.compile(r"\b(igy\w*|fogyassz\w*|javasol\w*|ajánl\w*|tanácsol\w*|kell)\b", re.IGNORECASE)
_NEGATION = re.compile(r"\b(ne|nem|kerülje|kerülendő|tilos|mellőz\w*|abbahagy\w*|elhagy\w*)\b", re.IGNORECASE)


def _hash(payload: dict) -> str:
    return hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()


def digest_prompt_hash() -> str:
    """A kivonat érvényessége: promptváltásnál (vagy más modellnél) a régi kivonat újraszámolandó."""
    return _hash({"kind": "document-digest", "version": DIGEST_VERSION, "system": DIGEST_SYSTEM, "schema": DIGEST_SCHEMA,
                  "options": _OPTIONS, "model": config.HISTORY_LLM_MODEL, "extractor": config.EXTRACTOR_VERSION})


def prompt_hash() -> str:
    return _hash({"kind": "medical-history", "history": SYSTEM_HISTORY, "recommendations": SYSTEM_RECOMMENDATIONS,
                  "options": _OPTIONS, "digest": digest_prompt_hash()})


def _model() -> dict:
    return {"modelName": config.HISTORY_LLM_MODEL, "seed": config.LLM_SEED, "promptHash": prompt_hash(),
            "extractorVersion": config.EXTRACTOR_VERSION}


def _chat_json(system: str, user: str) -> dict:
    resp = requests.post(
        f"{config.OLLAMA_URL}/api/chat",
        json={"model": config.HISTORY_LLM_MODEL,
              "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
              "format": DIGEST_SCHEMA, "stream": False,
              "options": {**_OPTIONS, "seed": config.LLM_SEED, "num_ctx": config.LLM_NUM_CTX,
                          "num_predict": DIGEST_MAX_TOKENS}},
        timeout=config.LLM_TIMEOUT_SEC,
    )
    resp.raise_for_status()
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


# ---------------------------------------------------------------- ellenőrzések


def suspicious(text: str | None) -> str | None:
    """Szakmailag szokatlan javaslat a szövegben (alkoholfogyasztás javaslata) — mondatonként; a tiltó
    mondat ("kerülje az alkoholt") nem az."""
    for sentence in re.split(r"(?<=[.!?;])\s+", text or ""):
        if _ALCOHOL.search(sentence) and _ADVICE.search(sentence) and not _NEGATION.search(sentence):
            return "Szakmailag szokatlan javaslat (alkoholfogyasztás) — orvosi ellenőrzés szükséges"
    return None


_BNO_IN_TEXT = re.compile(r"(?<![A-Za-z0-9])([A-Z])\s?(\d{2})(?:\s?\.?\s?([0-9A-Z]{1,3}))?(?![0-9])")


def _bno_norm(code: str) -> str:
    """Összevetési alak: pont nélkül, a magyar ötjegyű írásmód záró nulláit elhagyva ("N18.3" = "N1830")."""
    c = code.replace(".", "").replace(" ", "").upper()
    return c[:3] + c[3:].rstrip("0")


def bno_in_text(code: str, text: str) -> bool:
    """A BNO-kód szerepel-e a leletben (a szöveg BNO-alakú jelöltjeivel összevetve): "N18.3" = "N183" =
    "N 18.3" = "N1830", "I10" = "I1000". A szöveg szóközeit nem vonjuk össze: "Diagnózis K2190" a "SK2190"
    egybeolvadással nem lenne kód."""
    needle = _bno_norm(code)
    return any(_bno_norm("".join(g or "" for g in m.groups())) == needle for m in _BNO_IN_TEXT.finditer(text))


def quote_in_text(quote: str, text: str) -> bool:
    """Szó szerinti idézet: egyben, vagy ha a modell távoli részeket fűzött össze ("…", mondatok), akkor
    minden (legalább 15 karakteres) része külön-külön szó szerint a leletben van."""
    if contains_phrase(text, quote):
        return True
    parts = [p.strip(" .,;") for p in re.split(r"\s*(?:\.\.\.|…)\s*|(?<=[.!?])\s+", quote)]
    parts = [p for p in parts if len(p) >= 15]
    return len(parts) > 1 and all(contains_phrase(text, p) for p in parts)


_MASK = re.compile(r"(\bA\s+)?\[beteg\]", re.IGNORECASE)


def unmask(s: str | None) -> str | None:
    """A modell szövegében (nem az idézetben) a "[beteg]" maszk helyett "a beteg" / mondat elején "A beteg"."""
    if not s:
        return s
    out = _MASK.sub(lambda m: "A beteg" if m.group(1) and m.group(1)[0] == "A" else "a beteg", s)
    return re.sub(r"(^|[.!?]\s+)a beteg", lambda m: m.group(1) + "A beteg", out)


def _year_ok(date: str, text: str) -> bool:
    return date[:4] in text


def _clean(s, limit: int) -> str | None:
    if not isinstance(s, str):
        return None
    s = " ".join(s.split())
    return s[:limit] if s else None


def title_in_text(title: str, text: str) -> bool:
    """A cím a leletből jön-e: legalább egy érdemi (≥ 5 betűs) szavának töve (első 5 betű) szerepel a
    szövegben. A kis modell a prompt példájából vett át címet ("Nephrologiai konzílium" egy kardiológiai
    leletre) — ez ellen."""
    words = set(re.findall(r"[a-z]+", fold(text)))
    stems = {w[:5] for w in re.findall(r"[a-z]+", fold(title)) if len(w) >= 5}
    return not stems or any(w.startswith(st) for st in stems for w in words)


def _code_as_name(name: str, quote: str) -> tuple[str, str | None]:
    """Ha a modell a BNO-kódot írta névnek ("K2190"), a kód a kód, a név a sor kód nélküli része."""
    code = name.strip().upper().replace(" ", "")
    if not _BNO.match(code):
        return name, None
    rest = " ".join(re.sub(re.escape(name.strip()), " ", quote, flags=re.IGNORECASE).split()).strip(" -–:;,.()")
    return (rest or name), code


def make_digest(doc: dict) -> dict:
    """Egy dokumentum kivonata a modellel, ellenőrizve. A Spring-oldali Digest alakjában."""
    text = doc["text"]
    data = _chat_json(DIGEST_SYSTEM, f"Dokumentum ({doc.get('date')}):\n\n{text}")
    warnings: list[str] = []

    fallback = _TITLES.get(doc.get("type"), "Lelet")
    title = _clean(data.get("cim"), 200) or fallback
    if not title_in_text(title, text):
        warnings.append(f"A modell címe ({title[:60]}) nem a leletből származik — helyette: {fallback}")
        title = fallback
    place = _clean(data.get("hely"), 300)
    # a hely értékét nem írjuk a figyelmeztetésbe: a modell néha a beteg-fejlécet adta helynek
    if place and (not contains_phrase(text, place) or "[beteg]" in place or len(place) > 120):
        warnings.append("A modell által megadott hely nem szerepel szó szerint a leletben — kimaradt")
        place = None
    title = unmask(title)
    summary = unmask(_clean(data.get("osszefoglalo"), 2000))
    quote = _clean(data.get("idezet"), 2000)
    if quote and not quote_in_text(quote, text):
        warnings.append("A kivonat idézete nem található szó szerint a leletben — kimaradt")
        quote = None
    if not summary:
        if not quote:
            raise ValueError("A modell nem adott használható kivonatot")
        summary = quote

    events = []
    for e in data.get("korabbi_esemenyek") or []:
        date, etitle, equote = _clean(e.get("datum"), 10), _clean(e.get("cim"), 200), _clean(e.get("idezet"), 1000)
        if not (date and etitle and equote and _DATE.match(date)):
            continue
        if not quote_in_text(equote, text) or not _year_ok(date, equote):
            warnings.append(f"Korábbi esemény ({date}: {etitle}) nem támasztható alá idézettel — kimaradt")
            continue
        events.append({"date": date, "title": unmask(etitle), "quote": equote})

    diagnoses = []
    for d in data.get("diagnozisok") or []:
        name, dquote = _clean(d.get("nev"), 300), _clean(d.get("idezet"), 1000)
        if not (name and dquote):
            continue
        if not quote_in_text(dquote, text):
            warnings.append(f"Diagnózis ({name}) nem támasztható alá idézettel — kimaradt")
            continue
        name, code_from_name = _code_as_name(name, dquote)
        code = (d.get("bno") or "").strip().upper().replace(" ", "") or code_from_name
        if code and not (_BNO.match(code) and bno_in_text(code, text)):
            warnings.append(f"BNO-kód ({code}, {name}) nem szerepel a leletben — kimaradt (kódot a modell nem adhat)")
            code = None
        since = _clean(d.get("kezdet"), 10)
        if since and not (_DATE.match(since) and _year_ok(since, text)):
            since = None
        diagnoses.append({"name": unmask(name), "bnoCode": code, "since": since, "quote": dquote})

    return {"documentId": doc["id"], "title": title, "place": place, "summary": summary, "quote": quote,
            "events": events, "diagnoses": diagnoses, "warnings": [w[:500] for w in warnings],
            "model": {"modelName": config.HISTORY_LLM_MODEL, "seed": config.LLM_SEED,
                      "promptHash": digest_prompt_hash(), "extractorVersion": config.EXTRACTOR_VERSION},
            "promptHash": digest_prompt_hash()}


def _flag(digest: dict, text: str) -> str | None:
    """Az idővonal-elem jelölése — minden futásnál újraszámolva a kivonatból és a lelet szövegéből."""
    if (s := suspicious(digest["summary"])) or (s := suspicious(digest.get("quote"))):
        return s
    if not digest.get("quote"):
        return "Nem támasztható alá szó szerinti idézettel a leletből"
    numbers = unverified_numbers(digest["summary"], text)
    if numbers:
        return "A leletben nem szereplő szám: " + ", ".join(numbers)
    return None


# ---------------------------------------------------------------- idővonal, diagnózisok


def _lab_item(lab: dict) -> dict:
    abnormal = lab.get("abnormal") or []
    if abnormal:
        shown = [re.sub(r"\s*\(ref [^)]*\)", "", a) for a in abnormal[:LAB_TEXT_MAX]]
        text = "; ".join(shown)
        if len(abnormal) > LAB_TEXT_MAX:
            text += f"; további {len(abnormal) - LAB_TEXT_MAX} eltérés"
        text = "Referenciatartományon kívül: " + text
    else:
        text = f"{lab.get('total')} vizsgálat, mind a referenciatartományon belül"
    return {"date": str(lab["date"]), "title": "Laboreredmény", "text": text[:1500], "place": None,
            "documentId": lab["documentId"], "kind": "LAB", "flag": None}


def build_timeline(documents: list[dict], digests: dict[int, dict], labs: list[dict]) -> list[dict]:
    items = [_lab_item(lab) for lab in labs]
    seen_events: set[tuple[str, str]] = set()
    for doc in sorted(documents, key=lambda d: (str(d.get("date")), d["id"])):
        dg = digests[doc["id"]]
        items.append({"date": str(doc["date"]), "title": dg["title"], "text": dg["summary"], "place": dg.get("place"),
                      "documentId": doc["id"], "kind": "DOCUMENT", "flag": _flag(dg, doc["text"])})
        for e in dg.get("events") or []:
            # ugyanaz az anamnézis-adat több leletben is szerepel ("2010 óta hypertonia"): egyszer, a legkorábbiból
            key = (e["date"][:4] if len(e["date"]) == 4 else e["date"], fold(e["title"]))
            if key in seen_events:
                continue
            seen_events.add(key)
            items.append({"date": e["date"], "title": e["title"], "text": e["quote"], "place": None,
                          "documentId": doc["id"], "kind": "HISTORY", "flag": suspicious(e["quote"])})
    # év ("2010") a pontos dátum elé kerül; azonos napon a dokumentum sorrendje marad
    return sorted(items, key=lambda t: t["date"])


def build_diagnoses(documents: list[dict], digests: dict[int, dict]) -> list[dict]:
    """Dokumentumonkénti diagnózisok összefésülve (BNO-kód, ennek hiányában a név szerint); a dátum a
    legkorábbi kezdet / dokumentumdátum, a forrás az a dokumentum, amelyből a kód (vagy az első említés) jön."""
    merged: dict[str, dict] = {}
    for doc in sorted(documents, key=lambda d: (str(d.get("date")), d["id"])):
        for d in digests[doc["id"]].get("diagnoses") or []:
            key = (d.get("bnoCode") or "").replace(".", "") or fold(d["name"])
            by_name = next((k for k, v in merged.items() if fold(v["name"]) == fold(d["name"])), None)
            key = by_name or key
            date = d.get("since") or str(doc["date"])
            entry = {"name": d["name"], "bnoCode": d.get("bnoCode"), "date": date, "documentId": doc["id"]}
            old = merged.get(key)
            if old is None:
                merged[key] = entry
                continue
            if not old["bnoCode"] and entry["bnoCode"]:
                old.update(bnoCode=entry["bnoCode"], documentId=entry["documentId"])
            if date < old["date"]:
                old["date"] = date
    return sorted(merged.values(), key=lambda d: d["date"], reverse=True)


# ---------------------------------------------------------------- összefoglaló


def render_facts(facts: dict, timeline: list[dict], diagnoses: list[dict]) -> str:
    lines = []
    if facts.get("ageYears") is not None:
        lines.append(f"Életkor: {facts['ageYears']} év")
    lines.append("\nIdővonal (időrendben):")
    for t in timeline:
        if t.get("flag"):
            continue  # az ellenőrizendő elem nem megy a modellhez
        place = f" ({t['place']})" if t.get("place") else ""
        lines.append(f"- {t['date']} — {t['title']}{place}: {t.get('text') or ''}")
    if diagnoses:
        lines.append("\nDiagnózisok:")
        lines += [f"- {d['name']}" + (f" ({d['bnoCode']})" if d.get("bnoCode") else "") + f", {d['date']} óta / -tól"
                  for d in diagnoses]
    meds = facts.get("medications") or []
    if meds:
        lines.append("\nAktív gyógyszerek:")
        for m in meds:
            parts = [m["name"]] + [p for p in (m.get("activeIngredient"), m.get("dose"), m.get("route")) if p]
            lines.append("- " + ", ".join(parts) + ("" if m.get("verified") else " (nem ellenőrzött)"))
    allergies = facts.get("allergies") or []
    if allergies:
        lines.append("\nAllergiák: " + ", ".join(a["substance"] for a in allergies))
    return "\n".join(lines)


def _parse_list(text: str) -> list[str]:
    """A felsorolás sorai (mint summarize._parse_recommendations, de 6-os korláttal a hívónál)."""
    lines = text.splitlines()
    if any(_BULLET.match(l) for l in lines):
        lines = [l for l in lines if _BULLET.match(l)]
    return [x for x in (_BULLET.sub("", l).replace("**", "").strip() for l in lines) if len(x) > 3]


def _stream(system: str, user: str, max_tokens: int, loop_start) -> tuple[str, bool]:
    return stream_messages([{"role": "system", "content": system}, {"role": "user", "content": user}], max_tokens,
                           _OPTIONS, model=config.HISTORY_LLM_MODEL, loop_start=loop_start)


_KINDS = {"AMBULANCE": "ambuláns lap", "IMAGING": "képalkotó lelet"}


def build(facts: dict, progress=None) -> dict:
    """A Spring callback-jének törzse (DONE). progress(üzenet, lépés, összes): haladás a felületnek — a
    hiányzó kivonatok egyenként, majd az összefoglaló és a javaslatok (a 27b-vel lépésenként percek)."""
    warnings: list[str] = []
    documents = [d for d in facts.get("documents") or [] if d.get("text")]
    current = digest_prompt_hash()
    digests: dict[int, dict] = {}
    new_digests: list[dict] = []
    missing = [d for d in documents if not (d.get("digest") and d["digest"].get("promptHash") == current)]
    total = len(missing) + 2
    report = progress or (lambda message, step, total: None)
    for doc in documents:
        old = doc.get("digest")
        if old and old.get("promptHash") == current:
            digests[doc["id"]] = old
            continue
        step = missing.index(doc) + 1
        report(f"Dokumentum-kivonat {step}/{len(missing)}: {doc.get('date')} "
               f"{_KINDS.get(doc.get('type'), 'lelet')}", step, total)
        try:
            dg = make_digest(doc)
        except (ValueError, requests.RequestException) as e:
            # egy lelet hibája ne vigye el az egészet: kimarad, figyelmeztetéssel
            warnings.append(f"A(z) {doc.get('date')} dokumentum kivonata nem készült el ({e}) — kimaradt"[:500])
            continue
        digests[doc["id"]] = dg
        new_digests.append({k: v for k, v in dg.items() if k != "promptHash"})
    documents = [d for d in documents if d["id"] in digests]
    for doc in documents:
        warnings += [f"{doc['date']} {digests[doc['id']]['title']}: {w}" for w in digests[doc["id"]].get("warnings") or []]

    timeline = build_timeline(documents, digests, facts.get("labs") or [])
    if not timeline:
        raise ValueError("Egyik dokumentumból sem készült idővonal-elem")
    diagnoses = build_diagnoses(documents, digests)
    flagged = [t for t in timeline if t.get("flag")]
    if flagged:
        warnings.append(f"{len(flagged)} idővonal-elem ellenőrizendő (az összefoglaló nem épít rájuk): "
                        + "; ".join(f"{t['date']} {t['title']}" for t in flagged)[:400])

    facts_text = render_facts(facts, timeline, diagnoses)
    report("Anamnézis és kórtörténeti összefoglaló írása", total - 1, total)
    summary, cut1 = _stream(SYSTEM_HISTORY, "A beteg kórtörténetének tényei:\n\n" + facts_text, 1100,
                            _sentence_loop_start)
    if not summary:
        raise ValueError("A modell nem adott használható összefoglalót")
    report("Javaslatok", total, total)
    recs_text, cut2 = _stream(SYSTEM_RECOMMENDATIONS, "A beteg kórtörténetének tényei:\n\n" + facts_text, 450,
                              _loop_start)
    recommendations = []
    for r in _parse_list(recs_text):
        if suspicious(r):
            warnings.append(f"Szakmailag szokatlan javaslat kimaradt: {r}"[:500])
        elif r not in recommendations:
            recommendations.append(r)
    recommendations = recommendations[:MAX_RECOMMENDATIONS]

    # bekezdésenként: a dedupe a bekezdéshatárt nem tartaná meg
    paragraphs, repeated = [], 0
    for p in re.split(r"\n\s*\n", summary.strip()):
        kept, dropped = dedupe_sentences(p)
        repeated += dropped
        if kept:
            paragraphs.append(kept)
    summary = "\n\n".join(paragraphs)
    if cut1 or cut2 or repeated:
        warnings.append("A modell ismétlésbe kezdett vagy elérte a hosszkorlátot — a szöveg az utolsó teljes "
                        "mondatnál le lett zárva")
    if s := suspicious(summary):
        warnings.append("Összefoglaló: " + s)
    warnings += dose_mismatches(summary, facts)
    warnings += [f"Ellenőrizetlen szám az összefoglalóban: {n} (nem szerepel a beteg adatai között)"
                 for n in unverified_numbers(summary, facts_text)]
    warnings += [f"Ellenőrizetlen szám a javaslatokban: {n} (nem szerepel a beteg adatai között)"
                 for n in unverified_numbers(" ".join(recommendations), facts_text)]
    return {
        "status": "DONE",
        "summary": summary[:8000],
        "timeline": timeline,
        "diagnoses": diagnoses,
        "recommendations": [r[:500] for r in recommendations],
        "warnings": [w[:500] for w in warnings],
        "digests": new_digests,
        "model": _model(),
    }
