"""Szakirodalmi kutatás — a helyi modell feladatai (docs/literature-research.md).

1. context(): a beteg állapotának rövid, problémaközpontú összefoglalója (RESEARCH_LLM_MODEL, egy
   hívás) + angol PubMed-keresőkifejezés javaslat (RESEARCH_LLM_MODEL, az összefoglalóból). Az orvos
   mindkettőt átírhatja, mielőtt bármi kimenne a hálózatra.
2. evaluate(): cikkenként magyar összefoglaló + relevancia (HIGH/MEDIUM/LOW) + indoklás, a
   (szerkesztett) betegállapothoz és az orvos kiegészítő kéréséhez mérve (RESEARCH_LLM_MODEL).

A modell nem diagnosztizál és nem javasol terápiát: azt mondja meg, miről szól a cikk, és miért
lehet (vagy nem) érdekes ennél a betegnél. Determinisztikus őrök: az összefoglaló számai legyenek
benne az absztraktban; a keresőkifejezésből a hosszú számsorok (TAJ, dátum) kimaradnak.
"""
import hashlib
import json
import re

import requests

import config
from summarize import _sentence_loop_start, dedupe_sentences, render_facts, stream_messages, unverified_numbers

SYSTEM_CONTEXT = """Magyar klinikus asszisztens vagy. Egy beteg tényeiből rövid, problémaközpontú
állapot-összefoglalót írsz, amely alapján az orvos szakirodalmat fog keresni.
Szabályok:
- KIZÁRÓLAG a megadott tényeket használd. Ne találj ki diagnózist, értéket, gyógyszert.
- Gyógyszerből ne következtess diagnózisra (a béta-blokkolóból nem következik magas vérnyomás):
  diagnózist csak akkor írj, ha a tények között szerepel.
- 4–7 mondat, folyó szöveg: életkor; a fő diagnózisok és panaszok; a lényeges kóros eredmények
  (vizsgálat, labor); a jelenlegi gyógyszerek; az allergiák; ha kiderül, a nyitott klinikai kérdés.
- Ne írj nevet, azonosítót, lakóhelyet, pontos dátumot.
- Csak az összefoglaló szövegét írd, bevezetés és cím nélkül."""

SYSTEM_QUERY = """You turn a Hungarian clinical summary into ONE PubMed search query in English.
Rules:
- Use 2–3 key clinical concepts: the main diagnosis and the key problem or treatment question. Prefer
  MeSH terms; combine with AND / OR and parentheses. Keep it short enough to return results.
- Do not add allergies or routine medications unless they are the question itself.
- Never include names, identifiers, places, dates, ages, age filters or lab values.
- Output only the query on a single line, without quotes or explanation."""

SYSTEM_EVAL = """Magyar klinikus asszisztens vagy. Egy orvosnak segítesz szakirodalmat átnézni egy
konkrét beteghez. Megkapod a beteg állapotának összefoglalóját, az orvos esetleges kérését, és egy
cikk adatait (cím, típus, év, absztrakt angolul).
Válaszolj JSON-ban:
- "relevancia": HIGH (közvetlenül a beteg fő problémájáról / az orvos kérdéséről szól), MEDIUM
  (rokon téma, részben alkalmazható), LOW (alig kapcsolódik).
- "osszefoglalo": a cikk lényege magyarul, 2–4 mondatban: kérdés, módszer (pl. RCT, metaanalízis,
  áttekintés), fő eredmény. Számot csak az absztraktból idézz, pontosan. Ne ismételd magad.
- "indoklas": 1–2 mondat magyarul arról, miért (nem) releváns ennél a betegnél — a beteg mely
  adatához kapcsolódik. Ne adj diagnózist vagy terápiás utasítást; a döntés az orvosé.
Csak az absztraktban és a beteg-összefoglalóban álló tényekre támaszkodj."""

# Hosszkorlát a nyelvtanban: a 4B-s modell a "2–4 mondatot" figyelmen kívül hagyta, és az összefoglaló
# mezőben a tokenkorlátig ismételt (mérve, 2026-10-20). A relevancia elöl: rövid, mindig elkészül.
SUMMARY_MAX_CHARS = 900
REASON_MAX_CHARS = 500
EVAL_SCHEMA = {
    "type": "object",
    "properties": {
        "relevancia": {"type": "string", "enum": ["HIGH", "MEDIUM", "LOW"]},
        "osszefoglalo": {"type": "string", "maxLength": SUMMARY_MAX_CHARS},
        "indoklas": {"type": "string", "maxLength": REASON_MAX_CHARS},
    },
    "required": ["relevancia", "osszefoglalo", "indoklas"],
}

_OPTIONS = {"temperature": 0, "repeat_penalty": 1.1}
# A JSON-kimenetnél nincs ismétlés-büntetés: a kulcsok és idézőjelek ismétlődése a dolga, büntetve a
# 4B-s modell a szövegmezőben a hosszkorlátig hurkolt (mérve, 2026-10-20)
_EVAL_OPTIONS = {"temperature": 0}
EVAL_NUM_CTX = 8192
EVAL_MAX_TOKENS = 1200
MAX_QUERY_CHARS = 500
# ennél hosszabb számsor (TAJ, dátum, telefonszám) sosem kerülhet a kimenő keresésbe
_LONG_DIGITS = re.compile(r"\d{4,}")


def context_prompt_hash() -> str:
    payload = json.dumps({"kind": "research-context", "summary": SYSTEM_CONTEXT, "query": SYSTEM_QUERY,
                          "options": _OPTIONS, "extractor": config.EXTRACTOR_VERSION},
                         ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def eval_prompt_hash() -> str:
    payload = json.dumps({"kind": "research-eval", "system": SYSTEM_EVAL, "schema": EVAL_SCHEMA,
                          "options": _EVAL_OPTIONS, "num_ctx": EVAL_NUM_CTX, "extractor": config.EXTRACTOR_VERSION},
                         ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


# "72 years old"[Age], 72-year-old: a pontos életkor azonosíthat (és a Spring kapuja úgyis elutasítja)
_AGE = re.compile(r'"?\b\d{1,3}[- ]?(?:years?|yrs?)[- ]?old\b"?(?:\[[^\]]*\])?', re.IGNORECASE)
_COMPARISON = re.compile(r"\s*[<>=≤≥]+\s*\d+(?:[.,]\d+)?\s*%?")
_DANGLING = [(re.compile(r"\b(AND|OR|NOT)(\s+(?:AND|OR|NOT)\b)+"), r"\1"),
             (re.compile(r"\(\s*(?:AND|OR|NOT)\s+"), "("), (re.compile(r"\s+(?:AND|OR|NOT)\s*\)"), ")"),
             (re.compile(r"\(\s*\)"), ""), (re.compile(r"^\s*(?:AND|OR|NOT)\s+|\s+(?:AND|OR|NOT)\s*$"), "")]


def clean_query(raw: str) -> str:
    """Az első nem üres sor, "Query:" előtag és körülölelő idézőjel nélkül; a hosszú számsorok (TAJ,
    dátum) és a számos összehasonlítás ("EF < 30%") kimarad, a feleslegessé vált operátorokkal együtt."""
    line = next((l for l in raw.replace("`", "").splitlines() if l.strip()), "").strip()
    line = re.sub(r"^(?:pubmed\s+)?(?:search\s+)?query\s*:\s*", "", line, flags=re.IGNORECASE)
    if len(line) > 1 and line[0] in "\"'“„" and line[-1] in "\"'”" and line.count('"') <= 2:
        line = line[1:-1]
    line = _LONG_DIGITS.sub("", _COMPARISON.sub("", _AGE.sub("", line)))
    for _ in range(3):
        for pattern, repl in _DANGLING:
            line = pattern.sub(repl, line)
    return " ".join(line.split())[:MAX_QUERY_CHARS]


def suggest_query(summary: str) -> tuple[str, bool]:
    text, cut = stream_messages([{"role": "system", "content": SYSTEM_QUERY},
                                 {"role": "user", "content": summary}],
                                120, _OPTIONS, model=config.RESEARCH_LLM_MODEL, think=False)
    return clean_query(text), cut


def context(facts: dict) -> dict:
    """A Spring callback-jének törzse (DONE): összefoglaló + keresőkifejezés."""
    facts_text = render_facts(facts, include_labs=True)
    summary, cut = stream_messages([{"role": "system", "content": SYSTEM_CONTEXT},
                                    {"role": "user", "content": "A beteg tényei:\n\n" + facts_text}],
                                   600, _OPTIONS, model=config.RESEARCH_LLM_MODEL, loop_start=_sentence_loop_start,
                                   think=False)
    if not summary:
        raise ValueError("A modell nem adott használható összefoglalót")
    query, query_cut = suggest_query(summary)
    if not query:
        raise ValueError("A modell nem adott használható keresőkifejezést")
    warnings = []
    if cut or query_cut:
        warnings.append("A modell elérte a hosszkorlátot vagy ismétlésbe kezdett — a szöveg le lett zárva")
    warnings += [f"Ellenőrizetlen szám az összefoglalóban: {n} (nem szerepel a beteg adatai között)"
                 for n in unverified_numbers(summary, facts_text)]
    return {
        "status": "DONE",
        "summary": summary[:4000],
        "query": query,
        "warnings": [w[:500] for w in warnings],
        "model": {"modelName": config.RESEARCH_LLM_MODEL, "seed": config.LLM_SEED,
                  "promptHash": context_prompt_hash(), "extractorVersion": config.EXTRACTOR_VERSION,
                  "queryModelName": config.RESEARCH_LLM_MODEL},
    }


def render_article(article: dict) -> str:
    lines = [f"Cím: {article.get('title')}"]
    if article.get("publicationTypes"):
        lines.append("Típus: " + ", ".join(article["publicationTypes"]))
    if article.get("journal") or article.get("pubYear"):
        lines.append(f"Megjelent: {article.get('journal') or ''} {article.get('pubYear') or ''}".strip())
    lines.append("Absztrakt:\n" + (article.get("abstract") or ""))
    return "\n".join(lines)


def _chat_json(system: str, user: str) -> dict:
    resp = requests.post(
        f"{config.OLLAMA_URL}/api/chat",
        json={"model": config.RESEARCH_LLM_MODEL,
              "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
              "format": EVAL_SCHEMA, "stream": False, "think": False,
              "options": {**_EVAL_OPTIONS, "seed": config.LLM_SEED, "num_ctx": EVAL_NUM_CTX,
                          "num_predict": EVAL_MAX_TOKENS}},
        timeout=config.LLM_TIMEOUT_SEC,
    )
    resp.raise_for_status()
    body = resp.json()
    if "error" in body:
        raise ValueError(f"Ollama-hiba: {body['error']}")
    content = (body.get("message") or {}).get("content", "")
    if body.get("done_reason") == "length":
        raise ValueError("A modell válasza elérte a hosszkorlátot, nem teljes")
    try:
        data = json.loads(content)
    except json.JSONDecodeError as e:
        raise ValueError(f"A modell kimenete nem érvényes JSON: {e}") from e
    if not isinstance(data, dict):
        raise ValueError("A modell kimenete nem JSON-objektum")
    return data


def _whole_sentences(text: str, limit: int) -> str:
    """Ha a szöveg a hosszkorlátba ütközött, az utolsó teljes mondatig (a csonka mondat kimarad)."""
    text, _ = dedupe_sentences(text)
    if len(text) < limit - 5:
        return text
    last = max(text.rfind(". "), text.rfind("! "), text.rfind("? "), text.rfind("."))
    return text[:last + 1] if last > 0 else text


def evaluate(article: dict, context_text: str, extra_prompt: str | None) -> dict:
    """Egy cikk értékelése; a Spring callback-jének törzse (DONE)."""
    if not (article.get("abstract") or "").strip():
        raise ValueError("A cikknek nincs elérhető absztraktja — csak a címből nem értékelhető")
    user = "A beteg állapota (az orvos által jóváhagyott összefoglaló):\n" + context_text.strip()
    if extra_prompt and extra_prompt.strip():
        user += "\n\nAz orvos kérése / szempontja a kereséshez:\n" + extra_prompt.strip()
    user += "\n\nA cikk:\n" + render_article(article)
    data = _chat_json(SYSTEM_EVAL, user)
    summary = _whole_sentences(" ".join(str(data.get("osszefoglalo") or "").split()), SUMMARY_MAX_CHARS)
    reason = _whole_sentences(" ".join(str(data.get("indoklas") or "").split()), REASON_MAX_CHARS)
    relevance = data.get("relevancia")
    if not summary or not reason or relevance not in ("HIGH", "MEDIUM", "LOW"):
        raise ValueError("A modell válasza hiányos (összefoglaló, relevancia vagy indoklás nélkül)")
    warnings = []
    source_text = render_article(article) + "\n" + context_text
    warnings += [f"Ellenőrizetlen szám az összefoglalóban: {n} (nem szerepel az absztraktban)"
                 for n in unverified_numbers(summary, source_text)]
    return {
        "status": "DONE",
        "summary": summary[:2000],
        "relevance": relevance,
        "relevanceReason": reason[:1000],
        "warnings": [w[:500] for w in warnings],
        "model": {"modelName": config.RESEARCH_LLM_MODEL, "seed": config.LLM_SEED,
                  "promptHash": eval_prompt_hash()},
    }
