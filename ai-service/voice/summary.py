"""A felvétel összefoglalója a teljes átiratból, az orvos által jóváhagyott / hozzáadott
kulcsszavakból és a csoportok (panasz, diagnózis, beutaló, …) érvényes elemeiből (POST /recording-summaries).

Egyszerű szöveges hívás (nem kötött JSON): a docproc mérése szerint a 4B-s modell a JSON-kimenetben
hosszabb szövegnél hurokba esett (docproc/summarize.py). A modell mondatonként megszólalás-
sorszámra hivatkozik ([3] vagy [3, 5]); ezt determinisztikusan feldolgozzuk és ellenőrizzük:
  - a hivatkozott sorszám létezik (ha nem, a hivatkozás kimarad, figyelmeztetéssel);
  - hivatkozás nélküli mondatnál a forrást szótő-egyezéssel keressük meg (ground); ha így sincs,
    figyelmeztetést kap (nincs mögötte forrás);
  - az összefoglaló minden száma szerepel az átiratban (kitalált érték ellen);
  - a csoportok MINDEN eleme szerepel az összefoglalóban (laza szóegyezés, ld. covers): ami a modell
    kimenetéből kimaradt, determinisztikusan pótoljuk ("Diagnózis: sérv gyanú", a csoportelem forrásával),
    és a figyelmeztetés felsorolja;
  - az "X nem hangzott el / nem említette" mondat nem az átirat ténye: kimarad (nem figyelmeztetünk rá).
"""
import hashlib
import json
import logging
import re
import unicodedata

import requests

import beszed_pipeline as bp
import keywords as kw
import review

log = logging.getLogger("voice.summary")

SEED = 42
MAX_POINTS = 12
SPEAKER_LABEL = {"DOCTOR": "ORVOS", "PATIENT": "BETEG", "OTHER": "EGYÉB"}

PROMPT = {
    "sbo": """Sürgősségi osztályos orvos–beteg beszélgetés átiratát kapod, sorszámozott megszólalásokkal.
Írj belőle rövid, tárgyszerű klinikai összefoglalót egy orvosnak: fő panasz, a tünetek részletei (kezdet,
lokalizáció, jelleg), a tagadott tünetek, gyógyszerek, allergiák, előzmények.""",
    "echo": """Echokardiográfiás vizsgálat diktálásának átiratát kapod (csak az orvos megszólalásait), sorszámozott
megszólalásokkal.
Írj belőle rövid, tárgyszerű leletösszefoglalót egy orvosnak: a mért paraméterek értékkel és mértékegységgel,
pontosan úgy, ahogy elhangzottak, majd a szöveges megfigyelések.""",
}

RULES = """SZABÁLYOK:
- KIZÁRÓLAG az átiratban elhangzottakat használd. Ne állíts fel diagnózist, ne javasolj kezelést, ne találj ki adatot.
- Számot csak pontosan úgy írj, ahogy az átiratban áll.
- Legfeljebb {n} rövid mondat, mindegyik külön sorban, "- " kezdettel.
- Minden mondat végére írd szögletes zárójelben azoknak a megszólalásoknak a sorszámát, amelyekből az adat
  származik — az átirat sorainak elején álló szám, pl. [3] vagy [3, 5]; ne a saját mondataidat számozd.
- Ne írj arról, ami NEM hangzott el ("nem említette", "nem esett szó róla").
{keywords}{findings}Csak a felsorolást írd, bevezetés és cím nélkül."""

_REF = re.compile(r"\[(\d+(?:\s*,\s*\d+)*)\]")
_NUMBER = re.compile(r"\d+(?:[.,]\d+)?")


def _keywords_line(keywords: list[str]) -> str:
    if not keywords:
        return ""
    return ("- Az orvos ezeket a kulcsszavakat emelte ki — ha elhangzottak, mindegyikre térj ki: "
            + "; ".join(keywords) + "\n")


CATEGORY_LABEL = {"COMPLAINT": "Panasz", "DIAGNOSIS": "Diagnózis", "REFERRAL": "Beutaló",
                  "EXAM_FINDING": "Vizsgálati megállapítás", "MEDICATION": "Javasolt gyógyszer",
                  "RECOMMENDATION": "Javaslat / kontroll"}
# ennyi pont jöhet a modelltől + a pótolt csoportelemek (a Spring legfeljebb 30-at fogad)
MAX_TOTAL_POINTS = 30


def finding_label(f: dict) -> str:
    """A csoportelem egy sorban: "Diagnózis: sérv gyanú (BNO K40.9)"."""
    return (f"{CATEGORY_LABEL.get(f.get('category'), 'Csoportelem')}: {f['text']}"
            + (f" (BNO {f['bnoCode']})" if f.get("bnoCode") else ""))


def _findings_line(findings: list[dict]) -> str:
    if not findings:
        return ""
    items = "\n".join(f"  - {finding_label(f)}" + (f" {list(f['turnIds'])}" if f.get("turnIds") else "")
                      for f in findings)
    return ("- Az orvos a beszélgetésből ezeket az elemeket rögzítette — MINDEGYIK szerepeljen az összefoglalóban "
            "(a szögletes zárójelben a forrás-megszólalások):\n" + items + "\n")


def prompt_hash(mode: str) -> str:
    payload = json.dumps({"kind": "recording-summary", "prompt": PROMPT[mode], "rules": RULES, "options": _OPTIONS,
                          "doctorOnly": mode in DOCTOR_ONLY_MODES,
                          "model": review.REVIEW_MODEL}, ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


_STEM = 5
_STOP = {"beteg", "betegnek", "orvos", "panasz", "panaszkodik", "szerint", "elmondta", "mondta", "jelenleg"}


def _stems(text: str) -> set[str]:
    """Összevetési jegyek: a legalább 4 betűs szavak szótöve (első 5 betű), a nagybetűs rövidítések
    ("IVS", "HF", "LVF") és a számok — az echo-összefoglaló sorai ("IVS 9 mm") gyakran csak ezekből
    állnak, szótő nélkül semmihez nem kötődtek volna."""
    stems = {w[:_STEM] for w in re.findall(r"[0-9a-záéíóöőúüű]{4,}", text.lower()) if w not in _STOP}
    stems |= {w.lower() for w in re.findall(r"\b[A-ZÁÉÍÓÖŐÚÜŰ]{2,}\b", text)}
    stems |= {n.replace(",", ".") for n in _NUMBER.findall(text)}
    return stems


def ground(sentence: str, utterances: list[dict]) -> list[int]:
    """Determinisztikus forrás: azok a megszólalások, amelyekkel a mondat a legtöbb szótövet
    (első 5 betű) osztja — legalább 2-t (rövid mondatnál 1-et). A modell hivatkozása helyett /
    mellett: a kis modell gyakran nem ad, vagy rossz sorszámot ad."""
    stems = _stems(sentence)
    if not stems:
        return []
    need = 1 if len(stems) <= 2 else 2
    scored = [(len(stems & _stems(u["text"])), u["turnId"]) for u in utterances]
    best = max((n for n, _ in scored), default=0)
    if best < need:
        return []
    return sorted(t for n, t in scored if n == best)[:5]


# Laza szóegyezés (ragozás, ékezet): a szó első legfeljebb 5 betűje (ékezet nélkül) egy másik szó eleje. A 4
# betűs tő ("sérv") így a ragozott alakot ("sérve", "sérvre") is megtalálja — a _stems 5 betűs töve nem.
_FUZZY_STOP = {"beteg", "betegnek", "orvos", "gyanu", "gyanuja", "javasolt", "szerint", "jelenleg", "kerul",
               "kell", "lehet", "van", "nincs", "mint", "vagy", "es", "is", "a", "az", "egy"}


def _fold(text: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFKD", text.lower()) if not unicodedata.combining(c))


def _words(text: str) -> list[str]:
    return [w for w in re.findall(r"[0-9a-z]+", _fold(text)) if (len(w) >= 3 or w.isdigit()) and w not in _FUZZY_STOP]


def _word_in(word: str, words: list[str]) -> bool:
    if word.isdigit():
        return word in words
    stem = word[:5]
    return any(w.startswith(stem) or (len(w) >= 4 and word.startswith(w[:5])) for w in words)


def covers(text: str, needle: str, share: float = 0.6) -> bool:
    """A needle (pl. csoportelem) tartalmas szavainak legalább `share` része szerepel a text-ben (laza
    szóegyezéssel); 1–2 tartalmas szónál mind."""
    need = _words(needle)
    if not need:
        return False
    hay = _words(text)
    hit = sum(1 for w in need if _word_in(w, hay))
    return hit == len(need) if len(need) <= 2 else hit / len(need) >= share


def loose_ground(sentence: str, utterances: list[dict]) -> list[int]:
    """Laza forrás (a ground után): a megszólalás(ok), amely(ek) a mondat tartalmas szavainak legalább felét
    tartalmazzák (laza szóegyezés)."""
    need = _words(sentence)
    if not need:
        return []
    scored = [(sum(1 for w in need if _word_in(w, _words(u["text"]))), u["turnId"]) for u in utterances]
    best = max((n for n, _ in scored), default=0)
    if best == 0 or best < len(need) / 2:
        return []
    return sorted(t for n, t in scored if n == best)[:5]


# "nem említette", "nem esett szó", "nem hangzott el", "nincs adat" — nem az átirat ténye, hanem a hiánya
_NOT_MENTIONED = re.compile(r"\bnem\s+(?:említ|esett\s+szó|hangzott\s+el|tér\s+ki|beszélt|szerepel)|"
                            r"\bnincs\s+(?:adat|információ|említés)|\bnem\s+ismert\b|\bnem\s+derült\s+ki",
                            re.IGNORECASE)


def parse(text: str, utterances: list[dict], warnings: list[str], findings: list[dict] | None = None) -> list[dict]:
    turn_ids = {u["turnId"] for u in utterances}
    transcript = " ".join(u["text"] for u in utterances)
    points: list[dict] = []
    for line in text.splitlines():
        line = line.replace("**", "").replace("__", "").strip().lstrip("-•*#").strip()
        if not line or line.endswith(":"):
            continue  # üres sor, fejléc ("Összefoglaló:")
        refs: list[int] = []
        for m in _REF.finditer(line):
            refs.extend(int(x) for x in m.group(1).split(","))
        sentence = re.sub(r"\s+([.,;:!?])", r"\1", _REF.sub("", line)).strip().rstrip(" ,;")
        if not sentence:
            continue
        low = sentence.lower()
        if any(p["text"].lower() == low for p in points):
            continue  # a kis modell hajlamos ugyanazt a sort ismételni
        if sum(1 for p in points if p["text"].lower() in low) >= 2:
            continue  # a korábbi pontok egybefűzött ismétlése
        valid = sorted({r for r in refs if r in turn_ids})
        invalid = sorted({r for r in refs if r not in turn_ids})
        if not valid and _NOT_MENTIONED.search(sentence):
            log.info("„%s”: az el nem hangzott dologról szóló mondat kimaradt", sentence[:80])
            continue
        if not valid:
            # a mondat egy csoportelemet ír le: annak forrása (az orvos jóváhagyta)
            valid = next((sorted(t for t in f.get("turnIds") or [] if t in turn_ids)
                          for f in findings or [] if covers(sentence, f["text"])), [])
        if not valid:
            # A rossz sorszám (a modell gyakran a saját mondatait számozza) helyett a szótő-egyezés
            # dönt, utána a laza (ragozás-, ékezettűrő) szóegyezés; csak akkor figyelmeztetünk, ha így sem
            # köthető megszólaláshoz
            valid = ground(sentence, utterances) or loose_ground(sentence, utterances)
            if not valid:
                reason = f"nem létező megszólalásra hivatkozott ({invalid}), és " if invalid else ""
                warnings.append(f"„{sentence[:80]}”: {reason}nem köthető megszólaláshoz — forrás nélküli mondat, "
                                f"ellenőrizendő")
        elif invalid:
            log.info("„%s”: a nem létező hivatkozás (%s) kimaradt, a többi megmaradt", sentence[:80], invalid)
        for n in _NUMBER.findall(sentence):
            if n not in transcript and n.replace(",", ".") not in transcript and n.replace(".", ",") not in transcript:
                warnings.append(f"„{sentence[:80]}”: a(z) {n} szám nem szerepel az átiratban — ellenőrizendő")
        points.append({"text": sentence[:1000], "turnIds": valid[:20]})
        if len(points) >= MAX_POINTS:
            break
    return points


# Ismétlés-büntetés szélesebb ablakon (mint a docproc/summarize.py): a medgemma1.5:4b nélküle a
# felsorolás egy sorát hurokban ismételte; a kimenet hossza is korlátozott
_OPTIONS = {"temperature": 0, "seed": SEED, "repeat_penalty": 1.2, "repeat_last_n": 256, "num_ctx": 8192,
            "num_predict": 900}


def _generate(content: str) -> str:
    resp = requests.post(bp.OLLAMA_URL, json={
        "model": review.REVIEW_MODEL, "stream": False, "keep_alive": 0, "options": _OPTIONS,
        "messages": [{"role": "user", "content": content}]}, timeout=(10, bp.LLM_DEADLINE))
    resp.raise_for_status()
    data = resp.json()
    if "error" in data:
        raise RuntimeError(f"Ollama hiba: {data['error']}")
    return data.get("message", {}).get("content", "")


# Echo-diktálásnál a lelet az orvos diktálása: a beteg vagy más megszólalása (kérdés, közbeszólás)
# nem kerülhet az összefoglalóba — a felhasználó döntése (2026-10-15). Az átirat megmarad, csak a
# modell bemenetéből és a hivatkozható megszólalások közül marad ki.
DOCTOR_ONLY_MODES = {"echo"}


def add_missing_findings(points: list[dict], findings: list[dict], utterances: list[dict],
                         warnings: list[str]) -> None:
    """A csoportelem, amelyre a modell egyik pontja sem tér ki (covers), determinisztikusan bekerül: a
    csoport szövegével és forrás-megszólalásaival (ha azok a hivatkozhatók között vannak; különben szótő-
    egyezéssel). A figyelmeztetés felsorolja, mi került be így."""
    turn_ids = {u["turnId"] for u in utterances}
    added = []
    for f in findings:
        if any(covers(p["text"], f["text"]) for p in points):
            continue
        if len(points) >= MAX_TOTAL_POINTS:
            warnings.append(f"Csoportelem nem fért az összefoglalóba: {finding_label(f)}"[:500])
            continue
        refs = sorted(t for t in f.get("turnIds") or [] if t in turn_ids) or ground(f["text"], utterances)
        points.append({"text": finding_label(f)[:1000], "turnIds": refs[:20]})
        added.append(finding_label(f))
    if added:
        # nem figyelmeztetés: az elem bekerült (Szabi: ami szerepel, arra ne jelezzünk)
        log.info("%d csoportelem a modell összefoglalójából kimaradt, pótolva: %s", len(added), "; ".join(added))


def summarize(mode: str, utterances: list[dict], keywords: list[str], findings: list[dict] | None = None) -> dict:
    mode = mode.lower()
    findings = [f for f in findings or [] if (f.get("text") or "").strip()]
    warnings: list[str] = []
    if mode in DOCTOR_ONLY_MODES:
        doctor = [u for u in utterances if u.get("speaker") == "DOCTOR"]
        excluded = len(utterances) - len(doctor)
        if not doctor:
            raise RuntimeError("echo-diktálás: az átiratban nincs orvosi megszólalás, nincs miből összefoglalni")
        if excluded:
            warnings.append(f"Echo-diktálás: {excluded} nem orvosi (beteg / egyéb) megszólalás kimaradt az "
                            f"összefoglalóból")
        utterances = doctor
    dialog = "\n".join(f"[{u['turnId']}] {SPEAKER_LABEL.get(u['speaker'], 'EGYÉB')}: {u['text']}" for u in utterances)
    content = (PROMPT[mode] + "\n" + RULES.format(n=MAX_POINTS, keywords=_keywords_line(keywords),
                                                  findings=_findings_line(findings))
               + "\n\n--- ÁTIRAT ---\n" + dialog + "\n--- ÁTIRAT VÉGE ---")
    raw = _generate(content)
    points = parse(raw, utterances, warnings, findings)
    if not points and not findings:
        raise RuntimeError("a modell nem adott értelmezhető összefoglalót")
    add_missing_findings(points, findings, utterances, warnings)
    missing = [k for k in keywords if not kw.occurs(k, [{"text": raw}])]
    if missing:
        warnings.append(f"Az összefoglaló nem tér ki ezekre a kulcsszavakra: {', '.join(missing)}"[:500])
    log.info("Összefoglaló: %d pont, %d figyelmeztetés", len(points), len(warnings))
    return {
        "status": "DONE",
        "summary": "\n".join(p["text"] for p in points),
        "points": points,
        "warnings": [w[:500] for w in warnings][:30],
        "model": {"modelName": review.REVIEW_MODEL, "seed": SEED, "promptHash": prompt_hash(mode)},
    }
