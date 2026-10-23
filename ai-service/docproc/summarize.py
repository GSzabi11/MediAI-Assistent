"""Klinikai státusz: rövid összefoglaló + proaktív javaslatok a beteg elmúlt 6 hónapjáról.

A bemenet a Spring által összegyűjtött, már strukturált tények (laborsorok a szabálymotor
kiértékelésével, a leletekből kinyert állítások, gyógyszer, allergia, a hangfelvételek
összefoglalói) — nem nyers lelet-szöveg és nem átirat.
A "Fő eltérések" NEM a modelltől jön (a frontend a szabálymotor állításaiból mutatja).

Ellenőrzés (determinisztikus): a kimenetben szereplő minden "érdemi" szám (tizedes, vagy ≥ 10)
benne kell legyen a bemenetben — ha nincs, figyelmeztetésként kiírjuk (kitalált laborérték ellen).
"""
import hashlib
import json
import re
from collections import OrderedDict

import requests

import config
from textutil import fold

# Két külön, rövid, egyszerű szöveges hívás (nem kötött JSON): a 4B-s modell a közös JSON-kimenetben
# az összefoglaló mondatait tagmondatonként hurokban ismételte.
SYSTEM_SUMMARY = """Magyar klinikus asszisztens vagy. Egy beteg elmúlt 6 hónapjának tényeiből részletes
klinikai státusz-összefoglalót írsz egy orvosnak a dokumentumaiból (leletek, ambuláns lapok) és — ha
vannak — a hangfelvételek (orvos–beteg beszélgetés, diktálás) összefoglalóiból.
Szabályok:
- KIZÁRÓLAG a megadott tényeket használd. Ne találj ki diagnózist, értéket, gyógyszert.
- Számot (értéket, dátumot) csak a tényekből idézz, pontosan; ne számolj új értéket.
- 8–12 mondat, folyó szöveg, felsorolás nélkül, 2–4 bekezdésben (bekezdések között üres sor):
  1. a fő panasz és a diagnózisok, mikor és hogyan jelentkeztek;
  2. a vizsgálatok és a fontos eredményeik (pl. EKG, Holter, echo, MR), dátummal;
  3. a hangfelvételeken elhangzottak, ha vannak;
  4. a jelenlegi állapot, a gyógyszerek adaggal és az allergiák.
- A KULCSVIZSGÁLATOK listájának MINDEN elemét említsd a dátumával és az eredményével. Ha egy vizsgálat
  előjegyzése és az eredménye is szerepel, az eredményt írd (a vizsgálat megtörtént). Az eredményt
  tömörítsd, de a TAGADÁST ("nincs", "nem igazolódott") mindig tartsd meg; a normál eredményt
  foglald össze egyben (pl. "normál üregek, regurgitáció nincs").
- A jelenlegi gyógyszereket az "Aktív gyógyszerek" listából írd, az ott álló adaggal.
  Laborértékeket ne sorolj fel: azokat a felület külön mutatja.
- Minden mondat új információt hozzon; ugyanazt ne ismételd.
- Csak az összefoglaló szövegét írd, bevezetés és cím nélkül."""

SYSTEM_RECOMMENDATIONS = """Magyar klinikus asszisztens vagy. Egy beteg elmúlt 6 hónapjának tényei alapján
proaktív javaslatokat adsz egy orvosnak a dokumentumai és — ha vannak — a hangfelvételei alapján:
konkrét következő lépéseket (kontroll-labor, konzílium, vizsgálat, terápia felülvizsgálata).
Szabályok:
- Legfeljebb 5 javaslat, mindegyik külön sorban, "- " kezdettel, egy mondatban.
- Mindegyik egy megadott tényre (tartományon kívüli értékre, diagnózisra, gyógyszerre, a hangfelvételen
  elhangzott panaszra) épüljön.
- Feltételes mód ("javasolt", "mérlegelendő") — a döntés az orvosé.
- Ha nincs indokolt teendő, írd: "- Nincs sürgős teendő; rutin kontroll javasolt."
- Csak a felsorolást írd, bevezetés nélkül."""

# Ismétlés-büntetés szélesebb ablakon: a hurok ciklusa (~45 token) rövidebb volt az alapértelmezett
# 64 tokenes ablaknál is, mégis ismételt
_OPTIONS = {"temperature": 0, "repeat_penalty": 1.2, "repeat_last_n": 256}

MAX_RECOMMENDATIONS = 5
_NUMBER = re.compile(r"\d+(?:[.,]\d+)?")


def prompt_hash() -> str:
    payload = json.dumps({"kind": "clinical-summary", "summary": SYSTEM_SUMMARY,
                          "recommendations": SYSTEM_RECOMMENDATIONS, "options": _OPTIONS,
                          "extractor": config.EXTRACTOR_VERSION}, ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


_LABEL_ORDER = {label: i for i, label in enumerate(
    ["Diagnózis", "Panasz", "Anamnézis", "Terápia", "Gyógyszer", "Allergia", "Javaslat", "Vizsgálat", "Státusz"])}


# --- Kulcsvizsgálatok: a vizsgálati leletek (Holter, echo, MR, …) determinisztikusan, a lelet
# szövegéből. A 27b-s modell a részletes összefoglalóból az MR-t teljesen kihagyta, a Holtert csak
# előjegyzettként említette (a Holter-leletből a kinyerés egyetlen állítást sem hozott).
_RESULT_HEADINGS = ("vizsgalat eredmenye", "eredmeny", "velemeny", "epikrizis", "osszefoglalas")
# Személyazonosító sor (fejléc, élőláb): a modellnek név/TAJ/lakcím nem mehet (SummaryFacts elve)
_PII = re.compile(r"\bneve\b|\btaj\b|lakcim|lakhely|szuletes|\banyja\b|esetszam|\bkba\b|naplosor|\bcime?\b|"
                  r"telefon|\btel:|e-mail|mh\.kod|kartonszam|patient")
_EXAMS = [("holter", "Holter-EKG"), ("mr", "szív MR"), ("mri", "szív MR"), ("cmri", "szív MR"),
          ("echo", "echokardiográfia"), ("echokardiografia", "echokardiográfia"),
          ("echokardiografias", "echokardiográfia"), ("echocardiographia", "echokardiográfia"),
          ("tte", "echokardiográfia"), ("ct", "CT"), ("ultrahang", "ultrahang")]
# az összefoglalóban ezek bármelyike = a vizsgálat említve van
_EXAM_MENTION = {"Holter-EKG": ("holter",), "szív MR": ("mr", "mri", "cmri", "magneses"),
                 "echokardiográfia": ("echo", "echocard", "echokard", "tte"), "CT": ("ct",),
                 "ultrahang": ("ultrahang",)}
_MEASUREMENT_LINE = re.compile(r":\s*[-+<>]?\d|:\s*$|indikacio|minosege")
_SIGNATURE = re.compile(r"\s(?:dr\.|Dr\.)\s.*$")
_SECTION_HEAD = re.compile(r"^(?:vizsgálat eredménye|eredmény|leírás, vélemény|vélemény)\s*:?\s*", re.IGNORECASE)
EXAM_RESULT_CHARS = 320


def _exam_words(text: str) -> set[str]:
    words = set(re.findall(r"[a-z0-9]+", fold(text)))
    return {name for key, name in _EXAMS if key in words}


def exam_of(text: str) -> str | None:
    """A lelet vizsgálattípusa a fejlécéből, ha pontosan egy van. Fejléc: az eredmény-szakasz előtti
    rész (ilyen nélkül a beteg-adatok utolsó soráig) — a lelet törzse ("szív MR-re előjegyezték")
    nem számít."""
    lines = [l for l in text[:2400].splitlines() if l.strip()]
    end = next((i for i, l in enumerate(lines)
                if fold(l).strip(" :.").startswith(_RESULT_HEADINGS) and len(l.strip()) <= 60), None)
    if end is None:
        pii = [i for i, l in enumerate(lines) if _PII.search(fold(l))]
        end = pii[-1] + 1 if pii else 0
    found = _exam_words(" ".join(l for l in lines[:end] if not _PII.search(fold(l))))
    return found.pop() if len(found) == 1 else None


def exam_result(text: str) -> str | None:
    """A vizsgálati lelet eredménye szó szerint: a "Vélemény" szakasz, ha van (MR), különben a
    "Vizsgálat eredménye" szakasz a mérés-sorok nélkül; az aláírás levágva."""
    lines = text.splitlines()
    for headings in (("velemeny",), ("vizsgalat eredmenye", "eredmeny")):
        start = next((i for i, l in enumerate(lines)
                      if fold(l).strip(" :.").startswith(headings) and len(l.strip()) <= 60), None)
        if start is None:
            continue
        body = [l for l in lines[start + 1:] if l.strip() and not set(l.strip()) <= set("._-")
                and not _PII.search(fold(l)) and not _MEASUREMENT_LINE.search(fold(l))]
        joined = _SECTION_HEAD.sub("", _SIGNATURE.sub("", " ".join(" ".join(l.split()) for l in body))).strip()
        if joined:
            return joined[:EXAM_RESULT_CHARS].rsplit(" ", 1)[0] + " …" if len(joined) > EXAM_RESULT_CHARS else joined
    return None


def key_exams(facts: dict) -> list[tuple[str, str, str | None]]:
    """(dátum, vizsgálat, szó szerinti eredmény) a vizsgálati leletekre, időrendben."""
    out = []
    for d in facts.get("documents") or []:
        text = d.get("text") or ""
        exam = exam_of(text) if text else None
        if exam:
            out.append((str(d.get("date")), exam, exam_result(text)))
    return sorted(out)


def missing_exams(summary: str, exams: list[tuple[str, str, str | None]]) -> list[str]:
    words = set(re.findall(r"[a-z0-9]+", fold(summary)))
    out = []
    for date, exam, _ in exams:
        keys = _EXAM_MENTION.get(exam, (fold(exam),))
        if not any(w == k or w.startswith(k) and len(k) >= 4 for w in words for k in keys):
            out.append(f"Az összefoglaló nem említi a(z) {exam} vizsgálatot ({date}) — ellenőrizendő")
    return out


def render_facts(facts: dict, include_labs: bool = True) -> str:
    """A tények tömör, ember- és modell-olvasható szövegként (vizsgálatonként időrendben).
    include_labs=False: az összefoglaló bemenete — a felhasználó döntése (2026-10-14) szerint az
    összefoglaló csak a dokumentumok megállapításaiból és a hangfelvételek összefoglalóiból készül (a
    laborsorok listáját a modell szó szerint visszamondta). A laboreltérések a Fő eltérések chipeken
    (szabálymotor) látszanak; a javaslatok továbbra is a teljes adatot kapják."""
    lines = [f"Időszak: {facts.get('periodStart')} – {facts.get('periodEnd')}"]
    if facts.get("ageYears") is not None:
        lines.append(f"Életkor: {facts['ageYears']} év")
    if not include_labs and (exams := key_exams(facts)):
        lines.append("\nKULCSVIZSGÁLATOK (időrendben, az eredmény szó szerint a leletből — mindet említsd):")
        lines += [f"- {date} {exam}: {result or 'eredmény a leletben'}" for date, exam, result in exams]

    labs = sorted((facts.get("labs") or []) if include_labs else [], key=lambda l: (l["name"].casefold(), l["date"]))
    by_test: "OrderedDict[str, list]" = OrderedDict()
    for lab in labs:
        by_test.setdefault((lab["name"], lab.get("unit") or ""), []).append(lab)
    # A kis modell 30–40 laborérték között elveszett ("a CRP emelkedett", holott normál): a
    # részletes idősort csak azok a vizsgálatok kapják, amelyeknek van tartományon kívüli mérése;
    # a többi csak névvel szerepel, a "normál" minősítéssel együtt.
    elteres = OrderedDict((k, v) for k, v in by_test.items() if any(s.get("status") for s in v))
    normal = [name for (name, _), v in by_test.items() if not any(s.get("status") for s in v)]
    if elteres:
        lines.append("\nTartományon kívüli laboreredmények (vizsgálatonként időrendben; [felett/alatt/eltér] a "
                     "referencia-szabály szerint):")
        for (name, unit), series in elteres.items():
            ref = next((s["reference"] for s in reversed(series) if s.get("reference")), None)
            meta = ", ".join(p for p in (unit, f"ref {ref}" if ref else None) if p)
            head = f"- {name}" + (f" ({meta})" if meta else "")
            values = "; ".join(f"{s['date']} {s['value']}" + (f" [{s['status']}]" if s.get("status") else "")
                               for s in series)
            lines.append(f"{head}: {values}")
    if by_test and not elteres:
        lines.append("\nNincs tartományon kívüli laboreredmény.")
    if normal:
        lines.append("\nReferenciatartományon BELÜLI vizsgálatok (normál, nem kell kiemelni): " + ", ".join(normal))

    statements = facts.get("statements") or []
    if statements:
        lines.append("\nLeletekből kinyert adatok:")
        status_hu = {"ACCEPTED": "orvos elfogadta", "MODIFIED": "orvos javította", "PENDING": "jóváhagyásra vár"}
        # A kis modell sorban halad végig a listán: a lényeg (diagnózis, panasz) legyen elöl, a
        # részletes mérések (Vizsgálat, Státusz) a végén; címkén belül a legfrissebb elöl
        ordered = sorted(statements, key=lambda s: str(s.get("date")), reverse=True)
        ordered.sort(key=lambda s: _LABEL_ORDER.get(s.get("label"), len(_LABEL_ORDER)))
        for s in ordered:
            label = f"[{s['label']}] " if s.get("label") else ""
            lines.append(f"- {label}{s['text']} ({s['date']}; {status_hu.get(s.get('reviewStatus'), '')})")

    meds = facts.get("medications") or []
    if meds:
        lines.append("\nAktív gyógyszerek:")
        for m in meds:
            parts = [m["name"]] + [p for p in (m.get("activeIngredient"), m.get("dose"), m.get("route")) if p]
            lines.append("- " + ", ".join(parts) + ("" if m.get("verified") else " (nem ellenőrzött)"))

    allergies = facts.get("allergies") or []
    if allergies:
        lines.append("\nAllergiák: " + ", ".join(a["substance"] for a in allergies))

    docs = facts.get("documents") or []
    if docs:
        kinds = {"LAB": "laborlelet", "AMBULANCE": "ambuláns lap", "IMAGING": "képalkotó"}
        counts: dict[str, int] = {}
        for d in docs:
            counts[kinds.get(d["type"], d["type"])] = counts.get(kinds.get(d["type"], d["type"]), 0) + 1
        lines.append("\nDokumentumok az időszakban: " + ", ".join(f"{n} {k}" for k, n in counts.items()))

    recordings = facts.get("recordings") or []
    if recordings:
        modes = {"SBO": "sürgősségi beszélgetés", "ECHO": "echo-diktálás"}
        lines.append("\nHangfelvételek összefoglalói:")
        for r in recordings:
            kind = modes.get(r.get("mode"), "hangfelvétel")
            edited = "; orvos szerkesztette" if r.get("edited") else "; AI-összefoglaló"
            text = " ".join((r.get("text") or "").split())
            lines.append(f"- {r['date']} ({kind}{edited}): {text}")
    return "\n".join(lines)


def _norm_number(n: str) -> str:
    n = n.replace(",", ".")
    if "." in n:
        n = n.rstrip("0").rstrip(".")
    return n


def unverified_numbers(text: str, facts_text: str) -> list[str]:
    """Az "érdemi" számok (tizedes, vagy ≥ 10), amelyek a bemenetben nem szerepelnek."""
    known = {_norm_number(n) for n in _NUMBER.findall(facts_text)}
    out = []
    for n in _NUMBER.findall(text):
        significant = ("." in n or "," in n) or int(n.split(",")[0].split(".")[0]) >= 10
        if significant and _norm_number(n) not in known and n not in out:
            out.append(n)
    return out


_SENTENCE = re.compile(r"(?<=[.!?])\s+")
_ABNORMAL_WORDS = re.compile(r"emelked|magas|megnövek|fokozott|csökken|alacsony|kóros|eltér", re.IGNORECASE)


def dedupe_sentences(text: str) -> tuple[str, int]:
    """Az ismétlődő mondatok elhagyása (a modell hurok-hajlama ellen); (szöveg, elhagyott db)."""
    seen: set[str] = set()
    kept, dropped = [], 0
    for sentence in _SENTENCE.split(text.strip()):
        key = re.sub(r"\W+", " ", sentence).strip().casefold()
        if not key:
            continue
        if key in seen:
            dropped += 1
            continue
        seen.add(key)
        kept.append(sentence.strip())
    return " ".join(kept), dropped


_NO_ABNORMAL = re.compile(r"nincs(?:enek)?\s+(?:kóros\s+)?eltérés|nem\s+mutat\s+eltérést|minden\s+érték\s+normál",
                          re.IGNORECASE)


def drop_false_normal(text: str, facts: dict) -> tuple[str, bool]:
    """Ha van tartományon kívüli érték, a "nincs kóros eltérés" mondat hamis — elhagyjuk."""
    if not any(lab.get("status") for lab in facts.get("labs") or []):
        return text, False
    kept = [s for s in _SENTENCE.split(text) if not _NO_ABNORMAL.search(s)]
    dropped = len(kept) != len(_SENTENCE.split(text))
    return " ".join(kept).strip(), dropped


def contradictions(text: str, facts: dict) -> list[str]:
    """Ha egy mondat egy (a szabálymotor szerint) normál vizsgálatot eltérőnek nevez, jelezzük —
    a szabály a mérvadó, nem a modell."""
    status: dict[str, bool] = {}
    for lab in facts.get("labs") or []:
        name = lab["name"]
        status[name] = status.get(name, False) or bool(lab.get("status"))
    out = []
    for sentence in _SENTENCE.split(text):
        low = sentence.casefold()
        if not _ABNORMAL_WORDS.search(sentence):
            continue
        for name, abnormal in status.items():
            head = re.split(r"[ (]", name, maxsplit=1)[0].casefold()
            if not abnormal and len(head) >= 3 and re.search(r"\b" + re.escape(head) + r"\b", low):
                msg = f"Ellentmondás: a(z) {name} a referenciatartományon belül van, az összefoglaló mégis eltérést említ"
                if msg not in out:
                    out.append(msg)
    return out


_CLAUSE = re.compile(r"[,.;:!?\n]")


_BRACKETED = re.compile(r"\[[^\]]*\]")


def _clause_key(c: str) -> str:
    # a [..] jelölések (forrás-sorszám, "[orvos által jóváhagyva]") nem számítanak: ezek ismétlődése
    # nem hurok
    return re.sub(r"\W+", " ", _BRACKETED.sub(" ", c)).strip().casefold()


def _loop_start(text: str) -> int | None:
    """Ha egy (legalább 3 szavas) tagmondat megismétlődik, az ismétlés kezdete; különben None."""
    seen: set[str] = set()
    pos = 0
    for part in _CLAUSE.split(text):
        key = _clause_key(part)
        if len(key.split()) >= 3:
            if key in seen:
                return text.find(part, pos)
            seen.add(key)
        pos += len(part) + 1
    return None


_SENTENCE_BREAK = re.compile(r"(?<=[.!?])\s+(?=[A-ZÁÉÍÓÖŐÚÜŰ])")


def _sentence_loop_start(text: str) -> int | None:
    """A részletes összefoglalóhoz: csak egy (legalább 6 szavas) teljes MONDAT ismétlődése hurok. A
    tagmondat-szintű figyelés a hosszabb szövegben hamisan jelzett (a dátumok pontjainál törő
    darabok ismétlődtek), és a 2–3. mondatnál levágta a szöveget."""
    seen: set[str] = set()
    pos = 0
    for sentence in _SENTENCE_BREAK.split(text):
        start = text.find(sentence, pos)
        pos = start + len(sentence)
        if pos >= len(text):
            break  # az íródó mondat
        key = _clause_key(sentence)
        if len(key.split()) >= 6:
            if key in seen:
                return start
            seen.add(key)
    return None


def _stream_text(system: str, user: str, max_tokens: int, loop_start=_loop_start) -> tuple[str, bool]:
    return stream_messages([{"role": "system", "content": system}, {"role": "user", "content": user}], max_tokens,
                           model=config.SUMMARY_LLM_MODEL, loop_start=loop_start)


def stream_messages(messages: list[dict], max_tokens: int, options: dict | None = None, *,
                    model: str | None = None, loop_start=_loop_start,
                    think: bool | None = None) -> tuple[str, bool]:
    """Szöveges generálás streamelve; hurok (loop_start: alapból ismétlődő tagmondat) esetén azonnal
    leáll, és a hurok előtti utolsó teljes mondatig tartó részt adja vissza. (szöveg, volt-e
    hurok/csonkolás). think=False: a gondolkodó modelleknél (qwen3.5) kikapcsolja a gondolkodási
    fázist — a nem gondolkodó modellek figyelmen kívül hagyják."""
    out = ""
    cut = False
    body = {"model": model or config.LLM_MODEL,
            "messages": messages,
            "stream": True,
            "options": {**(_OPTIONS if options is None else options), "seed": config.LLM_SEED,
                        "num_ctx": config.LLM_NUM_CTX,
                        "num_predict": max_tokens}}
    if think is not None:
        body["think"] = think
    with requests.post(
        f"{config.OLLAMA_URL}/api/chat",
        json=body,
        stream=True, timeout=config.LLM_TIMEOUT_SEC,
    ) as resp:
        resp.raise_for_status()
        for line in resp.iter_lines():
            if not line:
                continue
            chunk = json.loads(line)
            if "error" in chunk:
                raise ValueError(f"Ollama-hiba: {chunk['error']}")
            out += chunk.get("message", {}).get("content", "")
            start = loop_start(out)
            if start is not None:
                out, cut = out[:start], True
                break
            if chunk.get("done"):
                cut = chunk.get("done_reason") == "length"
                break
    if cut:
        # az utolsó teljes mondatig (ha nincs ilyen, ameddig van)
        last = max(out.rfind("."), out.rfind("!"), out.rfind("?"), out.rfind("\n"))
        out = out[:last + 1] if last > 0 else out.rstrip(" ,;:") + "."
    return out.strip(), cut


_BULLET = re.compile(r"^\s*(?:[-•*→]|\d+[.)])\s*")


def _parse_recommendations(text: str) -> list[str]:
    lines = text.splitlines()
    # Ha van felsorolás, csak annak sorai javaslatok: a modell néha összefoglaló bekezdéssel kezdett,
    # ami különben az első "javaslat" lett
    if any(_BULLET.match(l) for l in lines):
        lines = [l for l in lines if _BULLET.match(l)]
    items = []
    for line in lines:
        line = _BULLET.sub("", line).replace("**", "").strip()
        if line and len(line) > 3:
            items.append(line)
    return list(OrderedDict.fromkeys(items))[:MAX_RECOMMENDATIONS]


_DOSE_IN_TEXT = re.compile(r"(\d+(?:[.,]\d+)?)\s*(mg|g|mcg|µg|ml|NE|IU)\b", re.IGNORECASE)


def dose_mismatches(text: str, facts: dict) -> list[str]:
    """Ha az összefoglaló egy aktív gyógyszert a gyógyszerlistától eltérő adaggal említ (a modell
    egy régebbi lelet adagját vette át: "Bisoprolol 2,5 mg este", holott a lista szerint 1,25 mg),
    figyelmeztetés. A szöveget nem írjuk át."""
    out = []
    for m in facts.get("medications") or []:
        dose = (m.get("dose") or "").split(" ")[0]
        name = m.get("name") or ""
        if not dose or len(name) < 3:
            continue
        for hit in re.finditer(re.escape(name.strip()), text, re.IGNORECASE):
            window = text[hit.end():hit.end() + 30]
            said = _DOSE_IN_TEXT.search(re.split(r"[;\n]|,\s*(?=[A-ZÁÉÍÓÖŐÚÜŰ])", window)[0])
            if said and _norm_number(said.group(1)) != _norm_number(dose):
                msg = (f"Eltérés a jelenlegi gyógyszerlistától — {name}: {said.group(1)} {said.group(2)} "
                       f"(lista: {m.get('dose')}); lehet, hogy egy korábbi lelet adata")
                if msg not in out:
                    out.append(msg)
    return out


def write_summary(facts: dict) -> tuple[str, bool, str]:
    """Az összefoglaló megírása (stream): (nyers szöveg, hurok/csonkolás volt-e, a modellnek átadott tények
    szövege). A Medical history is ezt használja (a felhasználó döntése, 2026-10-23: ott az összefoglaló
    ugyanez, csak a javaslatok nélkül)."""
    summary_facts_text = render_facts(facts, include_labs=False)
    summary, summary_cut = _stream_text(SYSTEM_SUMMARY, "A beteg tényei:\n\n" + summary_facts_text, 900,
                                       loop_start=_sentence_loop_start)
    if not summary:
        raise ValueError("A modell nem adott használható összefoglalót")
    return summary, summary_cut, summary_facts_text


def check_summary(summary: str, facts: dict, summary_facts_text: str) -> tuple[str, list[str]]:
    """Az összefoglaló determinisztikus utóellenőrzése: (javított szöveg, figyelmeztetések). A hurok /
    csonkolás jelzését a hívó teszi hozzá (a javaslatokéval együtt)."""
    warnings = []
    summary, denied = drop_false_normal(summary, facts)
    if denied:
        warnings.append("Az összefoglaló egy mondata szerint nincs kóros eltérés, holott a referencia-szabály "
                        "tartományon kívüli értéket talált — a mondatot elhagytuk")
    warnings += contradictions(summary, facts)
    warnings += missing_exams(summary, key_exams(facts))
    warnings += dose_mismatches(summary, facts)
    warnings += [f"Ellenőrizetlen szám az összefoglalóban: {n} (nem szerepel a beteg adatai között)"
                 for n in unverified_numbers(summary, summary_facts_text)]
    return summary, warnings


def summarize(facts: dict) -> dict:
    """Az Ollama-hívások és az ellenőrzés; a Spring callback-jének törzse (DONE)."""
    facts_text = render_facts(facts)
    summary, summary_cut, summary_facts_text = write_summary(facts)
    recs_text, recs_cut = _stream_text(SYSTEM_RECOMMENDATIONS, "A beteg tényei:\n\n" + facts_text, 350)
    recommendations = _parse_recommendations(recs_text)

    summary, repeated = dedupe_sentences(summary)
    summary, warnings = check_summary(summary, facts, summary_facts_text)
    if summary_cut or recs_cut or repeated:
        warnings.append("A modell ismétlésbe kezdett vagy elérte a hosszkorlátot — a szöveg az utolsó teljes "
                        "mondatnál le lett zárva")
    # a javaslatok is: egy normál vizsgálatot eltérőnek nevező javaslat ugyanúgy ellentmondás
    warnings += [w for w in contradictions(" ".join(recommendations), facts) if w not in warnings]
    warnings += [f"Ellenőrizetlen szám a javaslatokban: {n} (nem szerepel a beteg adatai között)"
                 for n in unverified_numbers(" ".join(recommendations), facts_text)]
    return {
        "status": "DONE",
        "summary": summary[:4000],
        "recommendations": [r[:500] for r in recommendations],
        "warnings": [w[:500] for w in warnings],
        "model": {"modelName": config.SUMMARY_LLM_MODEL, "seed": config.LLM_SEED, "promptHash": prompt_hash(),
                  "extractorVersion": config.EXTRACTOR_VERSION},
    }
