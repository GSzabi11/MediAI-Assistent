"""Kulcsszó-javaslatok az átiratból (MedGemma), determinisztikus visszakereséssel.

A modell csak JAVASOL; ami nem hangzott el az átiratban, az kimarad. Az egyezés szótő-alapú (a
magyar ragozás miatt: „mellkasi fájdalom” ↔ „mellkasi fájdalmat”): a kifejezés minden szavának
töve (az első STEM_LEN betű) szókezdetként, egymás után szerepel ugyanabban a megszólalásban.
Ugyanezt a szabályt használja a frontend kiemelése (lib/atiratKiemeles.tsx) — a kettő egyezzen.
"""
import json
import logging
import re

import beszed_pipeline as bp
import review

log = logging.getLogger("voice.keywords")

MAX_KEYWORDS = 12
STEM_LEN = 5

PROMPT = {
    "sbo": "sürgősségi orvos–beteg beszélgetés",
    "echo": "echokardiográfiás vizsgálat diktálásának",
}

RULES = """A {what} átiratából gyűjtsd ki a KONKRÉT klinikai kulcsszavakat (legfeljebb {n}): a beteg panaszai és
tünetei, a gyógyszerek NEVE, az allergének NEVE, a korábbi betegségek, a vizsgálati paraméterek és eltérések.
SZABÁLYOK:
- Minden kulcsszó SZÓ SZERINT szerepeljen az átiratban, ugyanúgy írva (1–3 szó).
- Konkrét legyen: ne "gyógyszer", hanem a gyógyszer neve; ne "allergia", hanem amire allergiás; ne "fájdalom", hanem "mellkasi fájdalom".
- Ne adj diagnózist, ami nem hangzott el; számot ne adj kulcsszónak; ne ismételd ugyanazt.
PÉLDA (más betegről, ne ezeket add vissza): "BETEG: Két napja fáj a hasam jobb oldalt, metformint szedek, a pollenre
allergiás vagyok." → {{"kulcsszavak": ["fáj a hasam", "jobb oldalt", "metformint", "pollenre"]}}
JSON: {{"kulcsszavak": ["...", "..."]}}"""


def _stem_pattern(term: str) -> re.Pattern | None:
    words = re.findall(r"[0-9a-záéíóöőúüű]+", term.lower())
    if not words or len(words) > 4:
        return None
    parts = [re.escape(w[:STEM_LEN]) + r"[a-záéíóöőúüű]*" for w in words]
    return re.compile(r"(?<![a-záéíóöőúüű0-9])" + r"[\s,.-]+".join(parts), re.IGNORECASE)


def find_in_transcript(term: str, chat: list[dict]) -> str | None:
    """A kifejezés első előfordulása az átiratban, AHOGY OTT ÁLL (a modell írásmódja helyett:
    „atorvasztin” → „atorvasztatint”) — a kulcsszó így mindig szó szerint az átiratból jön."""
    pattern = _stem_pattern(term)
    if pattern is None:
        return None
    for t in chat:
        m = pattern.search(t["text"])
        if m:
            return m.group(0).strip(" ,.-")
    return None


def occurs(term: str, chat: list[dict]) -> bool:
    return find_in_transcript(term, chat) is not None


def suggest(chat: list[dict], mode: str) -> tuple[list[str], str | None, list[str]]:
    """(kulcsszavak, modell vagy None, figyelmeztetések)."""
    dialog = "\n".join(f"[{t['id']}] {t['role'].upper()}: {t['text']}" for t in chat)
    content = (RULES.format(what=PROMPT[mode], n=MAX_KEYWORDS) + "\n\n--- ÁTIRAT ---\n" + dialog
               + "\n--- ÁTIRAT VÉGE ---\nCsak érvényes JSON objektumot adj vissza, semmi mást.")
    try:
        raw = bp._call_ollama(review.REVIEW_MODEL, content, "json", bp.LLM_DEADLINE, keep_loaded=False)
        data = json.loads(raw)
        items = data.get("kulcsszavak") if isinstance(data, dict) else None
        if not isinstance(items, list):
            raise RuntimeError("a válaszban nincs 'kulcsszavak' tömb")
    except Exception as e:  # noqa: BLE001 — kulcsszó nélkül is használható az átirat
        log.warning("Kulcsszó-javaslat nem készült: %s", e)
        return [], None, [f"Kulcsszó-javaslat nem készült ({review.REVIEW_MODEL}): {e}"[:500]]

    out, dropped, seen = [], [], set()
    for item in items:
        if not isinstance(item, str):
            continue
        suggested = " ".join(item.split())[:200]
        if not suggested or re.fullmatch(r"[\d\s.,%]+", suggested):
            continue
        term = find_in_transcript(suggested, chat)
        if term is None:
            dropped.append(suggested)
            continue
        if term.lower() in seen:
            continue
        seen.add(term.lower())
        out.append(term)
        if len(out) >= MAX_KEYWORDS:
            break
    warnings = [f"Kulcsszó-javaslat elvetve (nem hangzott el az átiratban): {', '.join(dropped)}"[:500]] if dropped else []
    log.info("Kulcsszavak: %d elfogadva, %d elvetve", len(out), len(dropped))
    return out, review.REVIEW_MODEL, warnings
