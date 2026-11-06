"""Szakirodalmi kutatás — a helyi modell feladatai (docs/literature-research.md).

1. context(): a beteg állapotának rövid, problémaközpontú összefoglalója (RESEARCH_LLM_MODEL, egy
   hívás) + angol PubMed-keresőkifejezés javaslat (RESEARCH_LLM_MODEL, az összefoglalóból). Az orvos
   mindkettőt átírhatja, mielőtt bármi kimenne a hálózatra.
2. rank(): a keresés UTÁN, az összes találatra: gyors előszűrés (cikkenként csak a relevancia-kategória),
   majd a HIGH-ok közül a legrelevánsabb (legfeljebb 3) kiválasztása, indoklással. Ebből a Spring
   oldalakon átívelő sorrendet számol, és a megnyitott oldal ebben a sorrendben értékelődik.
3. evaluate(): cikkenként magyar összefoglaló + relevancia (HIGH/MEDIUM/LOW) + indoklás, a
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

# A modell nem a kész kifejezést írja (szabad szövegben a qwen3.5:9b csak kulcsszavakat adott:
# „male adult palpitations reflux”, 2026-10-24), hanem fogalmakat JSON-ban; a PubMed-szintaxist a kód
# rakja össze (build_query) — így a zárójelezés és a mezőjelölők mindig helyesek.
# Fő probléma AND (bármelyik társult probléma): a korábbi 2–3, egymással AND-del kötött fogalomra (palpitáció +
# eozinopénia + reflux, ill. palpitáció + NSVT + metabolikus szindróma) a PubMed 0, ill. néhány mellékes találatot
# adott, a kéttagú változatban pedig a modell mindig a beteg „nyitott kérdését” (eozinopénia, NSVT) választotta
# második fogalomnak, a GERD–aritmia cikkeket hozó refluxot nem (a régi szabad szavas „palpitations reflux”
# hozta). VAGY-gyal a társult problémák mind bekerülnek, a PubMed relevancia-sorrendje és az AI-rangsor dönt
# (mérve, 2026-10-26, TAJ 200 403 111-es beteg).
SYSTEM_QUERY = """You turn a Hungarian clinical summary into the concepts of ONE PubMed search, in English.
The search is: main AND (related 1 OR related 2 OR related 3).
Rules:
- "main": the main problem (usually the presenting complaint or the main diagnosis).
- "related": 0–3 other diagnoses or problems of the patient from the summary that may be linked to the
  main problem (a possible cause, a comorbidity, a finding of its work-up, or the treatment question).
  An article about the main problem together with ANY of them will be found.
- For each concept: "mesh" is the exact MeSH heading (e.g. "Gastroesophageal Reflux", "Heart Failure");
  "synonyms" are 1–2 short free-text English variants as written in abstracts (e.g. "GERD", "reflux").
- Do not add allergies or routine medications unless they are the question itself.
- Never use sex, age or age group (male, female, adult, aged, elderly, child …), names, identifiers,
  places, dates or lab values as a concept or synonym."""

QUERY_MAX_RELATED = 3
QUERY_MAX_SYNONYMS = 2
QUERY_TERM_MAX_CHARS = 60
_CONCEPT_SCHEMA = {
    "type": "object",
    "properties": {"mesh": {"type": "string", "maxLength": QUERY_TERM_MAX_CHARS},
                   "synonyms": {"type": "array", "maxItems": QUERY_MAX_SYNONYMS,
                                "items": {"type": "string", "maxLength": QUERY_TERM_MAX_CHARS}}},
    "required": ["mesh", "synonyms"],
}
QUERY_SCHEMA = {
    "type": "object",
    "properties": {"main": _CONCEPT_SCHEMA,
                   "related": {"type": "array", "maxItems": QUERY_MAX_RELATED, "items": _CONCEPT_SCHEMA}},
    "required": ["main", "related"],
}

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

# Az előszűrés ugyanazt a skálát használja, mint az értékelés, de csak a kategóriát kéri (rövid kimenet:
# ~3 s/cikk a qwen3.5:9b-vel, mérve 2026-10-23), hogy az összes találat sorba rendezhető legyen.
SYSTEM_SCREEN = """Magyar klinikus asszisztens vagy. Egy orvos szakirodalmi keresésének találatait
sorolod be egy konkrét beteghez. Megkapod a beteg állapotának összefoglalóját, az orvos esetleges
kérését, és egy cikk adatait (cím, típus, év, absztrakt angolul).
Válaszolj JSON-ban, a "relevancia" mezőben:
- HIGH: közvetlenül a beteg fő problémájáról / az orvos kérdéséről szól, a beteghez hasonló
  betegcsoporton;
- MEDIUM: rokon téma, részben alkalmazható (pl. más betegcsoport, más kezelés ugyanarra);
- LOW: alig kapcsolódik.
Csak az absztraktban és a beteg-összefoglalóban álló tényekre támaszkodj."""

SCREEN_SCHEMA = {
    "type": "object",
    "properties": {"relevancia": {"type": "string", "enum": ["HIGH", "MEDIUM", "LOW"]}},
    "required": ["relevancia"],
}

SYSTEM_PICK = """Magyar klinikus asszisztens vagy. Egy orvos szakirodalmi keresésének előszűrt, a beteg
szempontjából relevánsnak ítélt cikkei közül kiválasztod a legrelevánsabbat (vagy legfeljebb hármat).
Szempontok, sorrendben: mennyire közvetlenül a beteg fő problémájáról / az orvos kérdéséről szól; mennyire
hasonlít a vizsgált betegcsoport a beteghez; a bizonyíték erőssége (metaanalízis, RCT, irányelv erősebb, mint
esetismertetés); frissesség.
Csak azt válaszd ki, ami valóban kiemelkedik; ha egy cikk egyértelműen a legjobb, csak azt.
Válaszolj JSON-ban: "valasztas" lista, a legrelevánsabb elöl, elemenként "sorszam" (a cikk sorszáma a
listában) és "indoklas" (1–2 mondat magyarul: miért ez a legrelevánsabb ennél a betegnél, a beteg mely
adatához kapcsolódik). Ne adj diagnózist vagy terápiás utasítást; a döntés az orvosé."""

# A választáshoz legfeljebb ennyi jelölt (a HIGH előszűrésűek, a forrás sorrendjében), rövidített
# absztrakttal: így a kérés belefér a kontextusba (8 × ~1200 karakter + a beteg)
PICK_MAX_CANDIDATES = 8
PICK_ABSTRACT_CHARS = 1200
PICK_MAX = 3
PICK_REASON_MAX_CHARS = 400
SCREEN_ABSTRACT_CHARS = 3000

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
                          "querySchema": QUERY_SCHEMA, "options": _OPTIONS, "queryOptions": _EVAL_OPTIONS,
                          "num_ctx": EVAL_NUM_CTX, "extractor": config.EXTRACTOR_VERSION},
                         ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def rank_prompt_hash() -> str:
    payload = json.dumps({"kind": "research-rank", "screen": SYSTEM_SCREEN, "screenSchema": SCREEN_SCHEMA,
                          "pick": SYSTEM_PICK, "pickMax": PICK_MAX, "candidates": PICK_MAX_CANDIDATES,
                          "pickAbstract": PICK_ABSTRACT_CHARS, "screenAbstract": SCREEN_ABSTRACT_CHARS,
                          "options": _EVAL_OPTIONS, "num_ctx": EVAL_NUM_CTX, "extractor": config.EXTRACTOR_VERSION},
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


# Nem és életkor(csoport) sosem kerül a kifejezésbe: nem a klinikai kérdés része, és a többi adattal
# együtt szűkíti, kiről van szó. (Az életkori MeSH-szűrő az orvos kézi kiegészítése lehet.)
_DEMOGRAPHIC = re.compile(
    r"\b(?:male|female|men|man|women|woman|boys?|girls?|adults?|aged|elderly|old(?:er)?|young|middle[- ]aged|"
    r"adolescents?|child(?:ren)?|infants?|newborns?|neonates?|pediatric|paediatric|geriatric|"
    r"humans?|patients?|years?|age|sex|gender)\b", re.IGNORECASE)
# a PubMed-szintaxis jelei: a modell kifejezésében nem maradhatnak (operátor, zárójel, mezőjelölő, csonkolás)
_SYNTAX = re.compile(r'[\[\]()"*:]|\b(?:AND|OR|NOT)\b')


def _term(raw) -> str:
    """Egy fogalom egy alakja tisztítva; üres, ha demográfiai szó vagy azonosító-gyanús."""
    term = " ".join(_SYNTAX.sub(" ", str(raw or "")).split()).strip(" ,;.")[:QUERY_TERM_MAX_CHARS]
    if not term or _LONG_DIGITS.search(term) or _AGE.search(term) or _COMPARISON.search(term):
        return ""
    # ha csak demográfiai szóból áll („Aged”, „Adult male”), kimarad; ha klinikai fogalomban áll
    # („Child Development Disorders”), a fogalom marad
    if not _DEMOGRAPHIC.sub("", term).strip(" -,"):
        return ""
    return term


def _forms(concept, max_synonyms: int) -> tuple[str, list[str]]:
    """(MeSH-fejléc, a szabad szavas alakok: a fejléc maga + a szinonimák), tisztítva."""
    if not isinstance(concept, dict):
        return "", []
    mesh = _term(concept.get("mesh"))
    synonyms = [s for s in (_term(s) for s in (concept.get("synonyms") or [])[:QUERY_MAX_SYNONYMS]) if s]
    return mesh, [t for t in [mesh] + synonyms[:max_synonyms] if t]


def build_query(main, related: list) -> str:
    """(Heading[MeSH Terms] OR Heading OR synonym …) AND (társult1 OR szinonima OR társult2 …). Idézőjel nélkül: így
    a PubMed a MeSH-belépési alakot a fejlécre képezi le („Chronic Kidney Disease” → Renal Insufficiency, Chronic),
    a nem létező fejlécet pedig a csoportból kihagyja (idézőjelesen ez a csoportot nullázhatja; „Palpitations” pl.
    nem MeSH-fejléc) — ezért kellenek mellé a szabad szavas alakok (mérve, 2026-10-24). Ezek mezőjelölő nélkül
    mennek: így a PubMed automatikus leképezése a többes számot, a MeSH-et és a szóváltozatokat is keresi
    („palpitations” → palpitation OR palpitations, „GERD” → Gastroesophageal Reflux[MeSH]); a [tiab] csak a pontos
    alakot, ezért kevesebb, kevésbé releváns találatot adott (mérve, 2026-10-26). A társult problémák egy csoportban,
    jelölő nélkül (az automatikus leképezés ott is a MeSH-re visz): így a kifejezés két ÉS-sel kötött fogalom marad,
    amit a felület chipjei bontani tudnak (lib/keresoKifejezes.ts). Ha nem fér a hosszkorlátba, a szinonimák
    fogynak (vágás a zárójelezést rontaná el)."""
    for max_synonyms in range(QUERY_MAX_SYNONYMS, -1, -1):
        seen = set()

        def unique(terms: list[str]) -> list[str]:
            out = []
            for t in terms:
                if t.lower() not in seen:
                    seen.add(t.lower())
                    out.append(t)
            return out

        mesh, forms = _forms(main, max_synonyms)
        main_parts = ([f"{mesh}[MeSH Terms]"] if mesh else []) + unique(forms)
        related_parts = unique([t for c in (related or [])[:QUERY_MAX_RELATED] for t in _forms(c, max_synonyms)[1]])
        groups = [p[0] if len(p) == 1 else "(" + " OR ".join(p) + ")" for p in (main_parts, related_parts) if p]
        query = " AND ".join(groups)
        if len(query) <= MAX_QUERY_CHARS:
            return query
    return ""


def suggest_query(summary: str) -> str:
    data = _chat_json(SYSTEM_QUERY, summary, QUERY_SCHEMA, 500)
    return clean_query(build_query(data.get("main"), data.get("related") or []))


def context(facts: dict) -> dict:
    """A Spring callback-jének törzse (DONE): összefoglaló + keresőkifejezés."""
    facts_text = render_facts(facts, include_labs=True)
    summary, cut = stream_messages([{"role": "system", "content": SYSTEM_CONTEXT},
                                    {"role": "user", "content": "A beteg tényei:\n\n" + facts_text}],
                                   600, _OPTIONS, model=config.RESEARCH_LLM_MODEL, loop_start=_sentence_loop_start,
                                   think=False)
    if not summary:
        raise ValueError("A modell nem adott használható összefoglalót")
    query = suggest_query(summary)
    if not query:
        raise ValueError("A modell nem adott használható keresőkifejezést")
    warnings = []
    if cut:
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


def render_article(article: dict, abstract_chars: int | None = None) -> str:
    lines = [f"Cím: {article.get('title')}"]
    if article.get("publicationTypes"):
        lines.append("Típus: " + ", ".join(article["publicationTypes"]))
    if article.get("journal") or article.get("pubYear"):
        lines.append(f"Megjelent: {article.get('journal') or ''} {article.get('pubYear') or ''}".strip())
    abstract = article.get("abstract") or ""
    if abstract_chars is not None and len(abstract) > abstract_chars:
        abstract = abstract[:abstract_chars].rsplit(" ", 1)[0] + " […]"
    lines.append("Absztrakt:\n" + abstract)
    return "\n".join(lines)


def _patient_block(context_text: str, extra_prompt: str | None) -> str:
    user = "A beteg állapota (az orvos által jóváhagyott összefoglaló):\n" + context_text.strip()
    if extra_prompt and extra_prompt.strip():
        user += "\n\nAz orvos kérése / szempontja a kereséshez:\n" + extra_prompt.strip()
    return user


# A num_ctx minden kutatás-hívásnál azonos (EVAL_NUM_CTX): eltérő értéknél az Ollama újratölti a modellt
def _chat_json(system: str, user: str, schema: dict = EVAL_SCHEMA, max_tokens: int = EVAL_MAX_TOKENS) -> dict:
    resp = requests.post(
        f"{config.OLLAMA_URL}/api/chat",
        json={"model": config.RESEARCH_LLM_MODEL,
              "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
              "format": schema, "stream": False, "think": False,
              "options": {**_EVAL_OPTIONS, "seed": config.LLM_SEED, "num_ctx": EVAL_NUM_CTX,
                          "num_predict": max_tokens}},
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
    user = _patient_block(context_text, extra_prompt) + "\n\nA cikk:\n" + render_article(article)
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


def screen(article: dict, context_text: str, extra_prompt: str | None) -> str:
    """Egy cikk előszűrése: csak a relevancia-kategória (HIGH / MEDIUM / LOW)."""
    user = (_patient_block(context_text, extra_prompt) + "\n\nA cikk:\n"
            + render_article(article, SCREEN_ABSTRACT_CHARS))
    relevance = _chat_json(SYSTEM_SCREEN, user, SCREEN_SCHEMA, 40).get("relevancia")
    if relevance not in ("HIGH", "MEDIUM", "LOW"):
        raise ValueError("A modell nem adott érvényes relevancia-kategóriát")
    return relevance


def pick(candidates: list[dict], context_text: str, extra_prompt: str | None) -> tuple[list[dict], list[str]]:
    """A jelöltek (HIGH előszűrésűek) közül a legrelevánsabb(ak): [{"id", "reason"}], a legjobb elöl."""
    n = len(candidates)
    schema = {
        "type": "object",
        "properties": {"valasztas": {"type": "array", "minItems": 1, "maxItems": min(PICK_MAX, n), "items": {
            "type": "object",
            "properties": {"sorszam": {"type": "integer", "enum": list(range(1, n + 1))},
                           "indoklas": {"type": "string", "maxLength": PICK_REASON_MAX_CHARS}},
            "required": ["sorszam", "indoklas"]}}},
        "required": ["valasztas"],
    }
    listing = "\n\n".join(f"[{i}] " + render_article(a, PICK_ABSTRACT_CHARS) for i, a in enumerate(candidates, 1))
    user = _patient_block(context_text, extra_prompt) + f"\n\nA cikkek ({n} db):\n\n" + listing
    data = _chat_json(SYSTEM_PICK, user, schema, 200 * PICK_MAX + 100)
    picks, seen, warnings = [], set(), []
    for item in data.get("valasztas") or []:
        index = item.get("sorszam") if isinstance(item, dict) else None
        # a nyelvtan a tartományon kívülit kizárja; az ismétlődő sorszám ellen kell
        if not isinstance(index, int) or not 1 <= index <= n or index in seen:
            continue
        seen.add(index)
        reason = _whole_sentences(" ".join(str(item.get("indoklas") or "").split()), PICK_REASON_MAX_CHARS)
        if not reason:
            continue
        article = candidates[index - 1]
        warnings += [f"Ellenőrizetlen szám a(z) „{article.get('title', '')[:60]}” indoklásában: {num}"
                     for num in unverified_numbers(reason, render_article(article) + "\n" + context_text)]
        picks.append({"articleId": article["id"], "reason": reason[:1000]})
    if not picks:
        raise ValueError("A modell nem választott érvényes cikket")
    return picks[:PICK_MAX], warnings


def _tournament(candidates: list[dict], context_text: str, extra_prompt: str | None) -> tuple[list[dict], list[str]]:
    """Ha több a jelölt, mint ami egy kérésbe fér: csoportonként (PICK_MAX_CANDIDATES) választ, a csoportok
    győztesei közül újra, amíg egy kérésbe nem fér (mérve: 20 találatból 12 kapott HIGH-ot, 2026-10-23).
    A végső indoklás az utolsó körből van; csak a végső kör ellenőrzéseit adjuk vissza."""
    while len(candidates) > PICK_MAX_CANDIDATES:
        winners = []
        for start in range(0, len(candidates), PICK_MAX_CANDIDATES):
            group = candidates[start:start + PICK_MAX_CANDIDATES]
            chosen, _ = pick(group, context_text, extra_prompt)
            by_id = {a["id"]: a for a in group}
            winners += [by_id[c["articleId"]] for c in chosen]
        candidates = winners
    return pick(candidates, context_text, extra_prompt)


def rank(articles: list[dict], context_text: str, extra_prompt: str | None, progress) -> dict:
    """Az összes találat előszűrése, majd a legrelevánsabb(ak) kiválasztása; a Spring callback-jének törzse.
    Absztrakt nélküli cikk nincs besorolva (a Spring a sor végére teszi). Egy cikk hibája nem buktatja el
    az egészet: kimarad, figyelmeztetéssel. Nincs HIGH → nincs kiemelés (a MEDIUM-ot nem emeljük ki)."""
    screened, warnings = [], []
    with_abstract = [a for a in articles if (a.get("abstract") or "").strip()]
    for i, article in enumerate(with_abstract, 1):
        progress(f"AI-előszűrés: {i} / {len(with_abstract)} cikk")
        try:
            screened.append({"articleId": article["id"],
                             "relevance": screen(article, context_text, extra_prompt)})
        except (ValueError, requests.RequestException) as e:
            warnings.append(f"A(z) „{article.get('title', '')[:60]}” előszűrése sikertelen: {e}")
    if with_abstract and not screened:
        raise ValueError("Egyetlen cikk előszűrése sem sikerült")
    high = {s["articleId"] for s in screened if s["relevance"] == "HIGH"}
    candidates = [a for a in with_abstract if a["id"] in high]
    picks = []
    if candidates:
        progress(f"A legrelevánsabb cikk kiválasztása ({len(candidates)} jelölt)…")
        picks, pick_warnings = _tournament(candidates, context_text, extra_prompt)
        warnings += pick_warnings
    return {
        "status": "DONE",
        "screened": screened,
        "picks": picks,
        "warnings": [w[:500] for w in warnings][:50],
        "model": {"modelName": config.RESEARCH_LLM_MODEL, "seed": config.LLM_SEED,
                  "promptHash": rank_prompt_hash()},
    }
