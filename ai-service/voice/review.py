"""MedGemma-átnézés: az átirat javítása a chat-nézet előtt — CÉLZOTT kérdésekkel.

Mérés (fejlesztői gép, 4B MedGemma): ha a teljes átiratot adjuk oda „javítsd, ahol hibás”
utasítással, a modell vagy semmit sem változtat, vagy tartalmat hagy el. Ezért a bizonytalan
helyeket determinisztikusan jelöljük ki, és csak ezekről kérdezünk:
  - szó: a Whisper szó-valószínűsége alacsony (transcription.uncertain_spans);
  - beszélő: a megszólalás hangja eltér a beszélője átlagától (az orvos hanglenyomatához mérve),
    vagy a tartalom nem illik a szerephez (a "beteg" kérdez, az "orvos" a saját panaszáról beszél);
  - érték (echo módban mindegyik, sbo-ban a bizonytalan): számérték mértékegységgel — ez soha nem
    íródik át, csak ellenőrizendőként jelöljük (review_note).
A modell válaszát determinisztikus őrök szűrik (szám, mértékegység, tagadás nem változhat; a csere
csak a kérdezett szakaszt érintheti). Az eredeti ASR-szöveg és -beszélő megmarad (utterance.asr_*).
"""
import difflib
import hashlib
import json
import logging
import os
import re
import time

import beszed_pipeline as bp

log = logging.getLogger("voice.review")

# Külön állítható (a .env LLM_MODEL-je a prototípus kinyerő modellje). Mérés (2026-10-12, 8 kérdéses
# próba-átirat): medgemma:latest — 2 szétszakadt gyógyszernév + 1 allergén javítva, 1 beszélőcsere
# helyes, az echo „555 százalék” ellenőrizendőként jelölve; medgemma1.5:4b — 1 javítás, beszélőcsere
# egy sem, a válaszai többször a kérdéshez sem illettek (az őrök elvetették).
REVIEW_MODEL = os.environ.get("VOICE_REVIEW_MODEL", "").strip() or bp.LLM_MODEL
BATCH_TURNS = 40          # ennyi megszólalás megy egy hívásba kontextusként
MAX_QUESTIONS = 30        # és legfeljebb ennyi kérdés
ROLES = ("orvos", "beteg", "egyéb")
# Megszólalás ennyivel közelebb / távolabb az orvos hangjától, mint a beszélője átlaga → kérdés
SPEAKER_SIM_MARGIN = 0.2

_WORD = re.compile(r"[0-9a-záéíóöőúüű]+", re.IGNORECASE)
_NUMBER = re.compile(r"\d+(?:[.,]\d+)?")
NEGATIONS = {"nem", "nincs", "nincsen", "sem", "se", "soha", "semmi", "sehol", "senki", "nélkül"}
UNIT_WORDS = ["milliméter", "centiméter", "méter per szekundum", "centiméter per szekundum", "méter", "milliliter",
              "liter", "szekundum", "másodperc", "milliszekundum", "százalék", "Hgmm", "mmHg", "mm", "cm", "ml",
              "m/s", "cm/s", "%", "fok", "ütés per perc", "per perc", "négyzetcentiméter"]
UNITS = {u.lower() for u in UNIT_WORDS}
# érték = szám + (opcionális) mértékegység, ahogy az átiratban áll
_VALUE = re.compile(r"\d+(?:[.,]\d+)?(?:\s*(?:" + "|".join(re.escape(u) for u in
                    sorted(UNIT_WORDS, key=len, reverse=True)) + r"))?", re.IGNORECASE)
# Nyelvi jel: az orvos első személyben a saját panaszáról / gyógyszeréről beszél, ill. a beteg kérdez
_FIRST_PERSON = re.compile(r"\b(fáj|fájt|szedek|szedtem|voltam|lettem|érzem|éreztem|vagyok|nekem|nálam|"
                           r"engem|kaptam|műtöttek|allergiás vagyok)\b", re.IGNORECASE)
_DOCTOR_QUESTION = re.compile(r"\b(szed|szedett|van|volt|érez|érzett|fáj|mióta|hová|mikor|kérem|"
                              r"vegyen|forduljon|mutassa|allergiája)\b", re.IGNORECASE)

PROMPT = """Egy {what} gépi átiratát kapod. Néhány helyen a gép bizonytalan volt; ezekről kérdezlek.

ÁTIRAT (a bizonytalan szavak «» között):
{dialog}

KÉRDÉSEK:
{questions}

Válaszolj minden kérdésre, a sorszámával.
- "szo" kérdés: add meg a helyes szót / kifejezést, ha a gép félreismerte (pl. orvosi szakszó, szétszakadt
  gyógyszernév), különben pontosan ugyanazt. Csak azt az egy szakaszt add meg, ne az egész mondatot. Új tartalmat ne adj hozzá.
- "ertek" kérdés: a számot NE javítsd. "gyanus": true, ha az érték az adott paraméternél valószínűtlen vagy félreérthető
  (pl. lehetetlen nagyság, hiányzó tizedesvessző, rossz mértékegység); különben false.
- "beszelo" kérdés: "orvos", "beteg" vagy "egyéb". Az orvos kérdez, vizsgál, utasít, diktál; a beteg a saját panaszairól,
  gyógyszereiről, előzményeiről beszél; az egyéb hozzátartozó vagy harmadik fél.
- "ok": egy rövid mondat indoklás.
JSON: {{"valaszok": [{{"k": <kérdés sorszáma>, "valasz": "...", "gyanus": false, "ok": "..."}}]}}"""

WHAT = {"sbo": "sürgősségi osztályos orvos–beteg beszélgetés",
        "echo": "echokardiográfiás vizsgálat diktálásának"}


def prompt_hash() -> str:
    return hashlib.sha256(PROMPT.encode("utf-8")).hexdigest()


def _words(text: str) -> list[str]:
    return [w.lower() for w in _WORD.findall(text)]


def _numbers(text: str) -> list[str]:
    return sorted(n.replace(",", ".") for n in _NUMBER.findall(text))


def _units(text: str) -> list[str]:
    lowered = " " + text.lower() + " "
    return sorted(u for u in UNITS if re.search(r"(?<![a-záéíóöőúüű])" + re.escape(u) + r"(?![a-záéíóöőúüű])",
                                                  lowered))


def _negations(text: str) -> int:
    return sum(1 for w in _words(text) if w in NEGATIONS)


def check_text_change(old: str, new: str) -> str | None:
    """None, ha a javítás elfogadható; különben az elutasítás oka."""
    if _numbers(old) != _numbers(new):
        return "számértéket változtatna"
    if _units(old) != _units(new):
        return "mértékegységet változtatna"
    if _negations(old) != _negations(new):
        return "tagadást változtatna"
    if not _words(new):
        return "üres szöveget adna"
    return None


def check_span_change(span: str, answer: str) -> str | None:
    """A kérdezett szakasz cseréje: legfeljebb 2 szóval hosszabb, és hasonlítson az eredetire
    (félreismert szó javítása, nem új mondat)."""
    sw, aw = _words(span), _words(answer)
    if not aw:
        return "üres választ adott"
    if len(aw) > len(sw) + 2:
        return "túl hosszú csere"
    ratio = difflib.SequenceMatcher(a=span.lower().replace(" ", ""), b=answer.lower().replace(" ", "")).ratio()
    if ratio < 0.5:
        return f"nem hasonlít az eredetire (hasonlóság {ratio:.2f})"
    return None


def _speaker_suspicious(turn: dict) -> bool:
    sim, base = turn.get("similarity"), turn.get("speaker_similarity")
    if sim is not None and base is not None:
        if turn["role"] == "orvos" and sim < base - SPEAKER_SIM_MARGIN:
            return True
        if turn["role"] != "orvos" and sim > base + SPEAKER_SIM_MARGIN:
            return True
    text = turn["text"]
    if turn["role"] != "orvos" and text.rstrip().endswith("?") and _DOCTOR_QUESTION.search(text):
        return True
    if turn["role"] == "orvos" and _FIRST_PERSON.search(text) and not text.rstrip().endswith("?"):
        return True
    return False


def build_questions(batch: list[dict], mode: str) -> list[dict]:
    questions = []
    for t in batch:
        spans = [s for s in t.get("uncertain", []) if s in t["text"]]
        for s in spans:
            kind = "ertek" if _NUMBER.search(s) else "szo"
            q = {"kind": kind, "turn": t, "span": s}
            if kind == "szo" and " " in s:
                # gyakori Whisper-hiba: szétszakadt szó (gyógyszernév) — a kis modell választani jobban tud
                q["options"] = [s, s.replace(" ", "")]
            questions.append(q)
        if mode == "echo" or spans:
            # echo: minden érték; sbo: a bizonytalan szakaszt érintő érték
            for m in _VALUE.finditer(t["text"]):
                v = m.group(0).strip()
                if mode == "echo" or any(v in s or s in v for s in spans):
                    if not any(q["span"] == v and q["turn"] is t for q in questions):
                        questions.append({"kind": "ertek", "turn": t, "span": v})
        if _speaker_suspicious(t):
            questions.append({"kind": "beszelo", "turn": t, "span": None})
    return questions[:MAX_QUESTIONS]


def _ask(mode: str, batch: list[dict], questions: list[dict]) -> list[dict]:
    lines = []
    for t in batch:
        marked = t["text"]
        for q in questions:
            if q["turn"] is t and q["span"] and f"«{q['span']}»" not in marked:
                marked = marked.replace(q["span"], f"«{q['span']}»", 1)
        lines.append(f"[{t['id']}] {t['role'].upper()}: {marked}")
    qtext = "\n".join(
        f"{k}. ({q['kind']}) [{q['turn']['id']}]: "
        + {"szo": f"«{q['span']}» helyesen?" + (" Lehetőségek: " + " / ".join(f"«{o}»" for o in q["options"])
                                                  + " — vagy írd be a helyeset." if q.get("options") else ""),
           "ertek": f"«{q['span']}» — valószínű érték?",
           "beszelo": f"„{q['turn']['text'][:80]}” — ki mondta ezt valójában?"}[q["kind"]]
        for k, q in enumerate(questions))
    content = PROMPT.format(what=WHAT[mode], dialog="\n".join(lines), questions=qtext) + \
        "\nCsak érvényes JSON objektumot adj vissza, semmi mást."
    last: Exception | None = None
    for temp in (0.0, 0.3):
        try:
            raw = bp._call_ollama(REVIEW_MODEL, content, "json", bp.LLM_DEADLINE, keep_loaded=False,
                                  temperature=temp)
            data = json.loads(raw)
            items = data.get("valaszok") if isinstance(data, dict) else None
            if not isinstance(items, list):
                raise RuntimeError("a válaszban nincs 'valaszok' tömb")
            return [i for i in items if isinstance(i, dict)]
        except (json.JSONDecodeError, RuntimeError) as e:
            last = e
    raise RuntimeError(f"a modell kimenete kétszer is érvénytelen volt: {last}")


def _note(turn: dict, text: str) -> None:
    prev = turn.get("review_note")
    turn["review_note"] = (f"{prev} {text}" if prev else text)[:500]


def _apply(questions: list[dict], answers: list[dict], warnings: list[str]) -> tuple[int, int]:
    accepted = rejected = 0
    for a in answers:
        k = a.get("k")
        if not isinstance(k, int) or not 0 <= k < len(questions):
            continue
        q, turn = questions[k], questions[k]["turn"]
        answer = a.get("valasz").strip().strip("«»\"'") if isinstance(a.get("valasz"), str) else ""
        why = a.get("ok").strip() if isinstance(a.get("ok"), str) else ""
        label = f"{turn['id']}. megszólalás"
        if q["kind"] == "ertek":
            if a.get("gyanus") is True:
                _note(turn, f"Érték ellenőrizendő: «{q['span']}»" + (f" — {why}" if why else ""))
            continue
        if q["kind"] == "beszelo":
            answer = answer.lower()
            if answer in ROLES and answer != turn["role"]:
                turn.setdefault("asr_role", turn["role"])
                turn["role"] = answer
                turn.setdefault("_reasons", []).append(f"beszélő: {why}" if why else "beszélő")
                accepted += 1
            continue
        # szo
        if not answer or answer == q["span"]:
            continue
        problem = check_span_change(q["span"], answer)
        new_text = turn["text"].replace(q["span"], answer, 1)
        problem = problem or check_text_change(turn["text"], new_text)
        if problem:
            rejected += 1
            warnings.append(f"AI-javítás elvetve ({label}): «{q['span']}» → «{answer[:100]}»: {problem}")
            if problem in ("számértéket változtatna", "mértékegységet változtatna"):
                _note(turn, f"Érték ellenőrizendő: «{q['span']}» (az AI szerint «{answer[:60]}» lehet)")
            continue
        turn.setdefault("asr_text", turn["text"])
        turn["text"] = new_text
        turn.setdefault("_reasons", []).append(f"«{q['span']}» → «{answer}»" + (f" ({why})" if why else ""))
        accepted += 1
    return accepted, rejected


def review(chat: list[dict], mode: str) -> tuple[list[dict], str | None, list[str]]:
    """(javított chat, a modell neve vagy None, figyelmeztetések). A chat elemei kiegészülnek:
    asr_text / asr_role (az eredeti, ha változott), correction_reason, review_note."""
    warnings: list[str] = []
    started = time.perf_counter()
    accepted = rejected = asked = 0
    try:
        for i in range(0, len(chat), BATCH_TURNS):
            batch = chat[i:i + BATCH_TURNS]
            questions = build_questions(batch, mode)
            if not questions:
                continue
            asked += len(questions)
            a, r = _apply(questions, _ask(mode, batch, questions), warnings)
            accepted += a
            rejected += r
    except Exception as e:  # noqa: BLE001 — az átnézés elmaradása nem bukatja a felvételt
        log.warning("Az AI-átnézés nem futott le: %s", e)
        for t in chat:  # félbemaradt átnézésnél ne maradjon fél-javítás
            if "asr_text" in t:
                t["text"] = t.pop("asr_text")
            if "asr_role" in t:
                t["role"] = t.pop("asr_role")
            t.pop("_reasons", None)
            t.pop("review_note", None)
        return chat, None, [f"Az AI-átnézés ({REVIEW_MODEL}) nem futott le, az átirat javítás nélküli: {e}"[:500]]
    for t in chat:
        reasons = t.pop("_reasons", None)
        if reasons:
            t["correction_reason"] = ("AI-javítás: " + "; ".join(reasons))[:500]
    log.info("AI-átnézés: %d kérdés, %d javítás elfogadva, %d elvetve, %.1f s",
             asked, accepted, rejected, time.perf_counter() - started)
    return chat, REVIEW_MODEL, warnings
