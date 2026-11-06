"""Strukturált kinyerés (extract()) mérése — javított kiértékeléssel (IDK C1. táblázat, M8).

Ugyanaz az 5 kitalált SBO-párbeszéd és aranystandard, mint a voice/llm_teszt.py-ban (onnan importálva,
hogy egyetlen forrása legyen), de a pontozás független a pipeline saját figyelmeztetéseitől, és a
korábbi kiértékelő hibáit javítja:

  1. Érvénytelen / üres kimenet SIKERTELEN futásként számít (a régi 0 pontot adott, de nem jelezte;
     a 2026-09-16-i nyers fájlban a MedGemma 4B a mellkas és a hasi esetben mindig üres JSON-t adott).
  2. Precizitás is: minden kinyert elem vagy egy elvárt elemnek felel meg, vagy „többlet”. A többletet
     a szkript besorolja (elhangzott / nem hangzott el), és a listát a kézi átnézéshez kiírja.
  3. Szótő csak szó elején illeszkedik („kar” ≠ „akar”), a régi részszöveg-egyezés helyett.
  4. Téves tagadás: jelen lévő tünet tagadottként is (nem csak a meg nem válaszolt kérdés).
  5. Forrás-ellenőrzés függetlenül: a hivatkozott megszólalásban (vagy az előtte álló kérdésben) ott
     van-e az elvárt elem szótöve — nem a pipeline ROSSZ FORRÁS figyelmeztetéseinek száma.
  6. Gyógyszer ↔ allergia felcserélése súlyos hiba.
  7. Az idő mediánja és az első (modellbetöltő) hívás külön.

Használat (az ai-service/voice ai_env környezetével, mert a beszed_pipeline onnan importál):
  ..\\voice\\ai_env\\Scripts\\python extraction_eval.py medgemma:latest qwen3.5:9b medgemma:27b --runs 3
  ..\\voice\\ai_env\\Scripts\\python extraction_eval.py --rescore results\\extraction_raw_….json
A nyers modellkimenet a results/ mappába kerül; a --rescore a modell újrafuttatása nélkül pontoz újra.
"""
import argparse
import difflib
import json
import os
import re
import statistics
import sys
import time
import unicodedata
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
VOICE = HERE.parent / "voice"
RESULTS = HERE / "results"

# ---------------------------------------------------------------------------
# AZ ARANYSTANDARD (voice/llm_teszt.py CASES) — itt csak olvassuk
# ---------------------------------------------------------------------------
sys.path.insert(0, str(VOICE))


def load_cases():
    # a llm_teszt a beszed_pipeline-t importálja (torch, whisper) — a pontozáshoz ez nem kell,
    # ezért a CASES-t a forrásból olvassuk ki, végrehajtás nélküli importtal
    import ast
    src = (VOICE / "llm_teszt.py").read_text(encoding="utf-8")
    tree = ast.parse(src)
    env = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id in ("HANYAS", "CASES")
                                                for t in node.targets):
            exec(compile(ast.Module([node], []), "llm_teszt.py", "exec"), env)
    return env["CASES"]


CASES = load_cases()
CASE_BY_ID = {c["id"]: c for c in CASES}

# A régi szótövek a szórendben eltérő alakot nem ismerték fel („fáj a feje” = fejfájás).
# Csak bővítünk (szinonima), szűkítés nincs; minden bővítés itt látszik.
ALIASES = {"fejfájás": ["feje", "fáj a fej"]}
for _c in CASES:
    for _key in ("present", "absent", "not_denied"):
        _c[_key] = [(n, st + [a for k, al in ALIASES.items() if k in n for a in al])
                    for n, st in _c.get(_key, [])]

# ---------------------------------------------------------------------------
# ILLESZTÉS
# ---------------------------------------------------------------------------
LETTER = "a-záéíóöőúüű0-9"
NONE_WORDS = re.compile(r"^\s*(nincs|nem ismert|nem tud|semmi|nem szed|-|n/a|none|null)", re.I)
STOP = {"nincs", "van", "volt", "nem", "igen", "kicsit", "nagyon", "talán", "egy", "és", "vagy", "mint",
        "beteg", "orvos", "ma", "reggel", "este", "tegnap", "óta", "órája", "napja", "panasz", "tünet"}


def matches(text: str, stems: list[str]) -> bool:
    """Szótő szó elején (a 'kar' a 'karba'-t igen, az 'akar'-t nem); '!'-es tő kizár."""
    text = (text or "").lower()
    def hit(s):
        return re.search(rf"(?<![{LETTER}]){re.escape(s)}", text) is not None
    inc = [s for s in stems if not s.startswith("!")]
    exc = [s[1:] for s in stems if s.startswith("!")]
    return any(hit(s) for s in inc) and not any(hit(s) for s in exc)


def sym_text(s: dict, fields=("tunet", "lokalizacio", "jelleg_sulyossag")) -> str:
    return " ".join(str(s.get(k) or "") for k in fields)


def real_items(lst) -> list[str]:
    return [x.strip() for x in (lst or []) if isinstance(x, str) and x.strip() and not NONE_WORDS.match(x)]


def plain(text: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFKD", (text or "").lower()) if not unicodedata.combining(c))


def name_match(x: str, stems: list[str]) -> bool:
    """Gyógyszer / allergia ékezettől függetlenül („ibuprofén” = „ibuprofen”: a magyar írásmód is helyes)."""
    return matches(plain(x), [plain(s) for s in stems])


def distorted(x: str, stems: list[str]) -> bool:
    """Eltorzított név: nem egyezik, de egy elvárt névhez nagyon hasonló („atorvasztantin”)."""
    words = plain(x).split()
    return bool(words) and any(
        difflib.SequenceMatcher(None, words[0][:len(s) + 2], plain(s)).ratio() >= 0.8
        for s in stems if not s.startswith("!") and len(s) >= 5)


def content_stems(text: str) -> list[str]:
    words = re.findall(rf"[{LETTER}]{{4,}}", (text or "").lower())
    return [w[:5] for w in words if w not in STOP]


def spoken(text: str, case: dict, roles=("beteg", "hozzátartozó")) -> bool:
    """A szöveg legalább egy tartalmas szótöve elhangzott-e a beteg / hozzátartozó szájából."""
    stems = content_stems(text)
    said = " ".join(t for r, t in case["dialog"] if r in roles)
    return bool(stems) and any(matches(said, [s]) for s in stems)


def source_ok(item: dict, stems: list[str], case: dict) -> bool:
    """A hivatkozott megszólalás(ok)ban vagy az előttük álló kérdésben ott van az elvárt elem."""
    ids = [i for i in (item.get("forras_turn_ids") or []) if isinstance(i, int) and 0 <= i < len(case["dialog"])]
    if not ids:
        return False
    ctx = set(ids) | {i - 1 for i in ids if i > 0}
    # a kizáró tő ('!hányinger') csak az elem nevére vonatkozik: a kérdés („Hányt, vagy volt
    # hányingere?”) forrásként helyes
    return matches(" ".join(case["dialog"][i][1] for i in sorted(ctx)), [s for s in stems if not s.startswith("!")])


# ---------------------------------------------------------------------------
# PONTOZÁS
# ---------------------------------------------------------------------------
def score(result: dict | None, case: dict) -> dict:
    sc = {"recall_hit": 0, "recall_max": 0, "by_cat": {}, "missing": [], "severe": [], "false_negation": [],
          "extra_spoken": [], "extra_unspoken": [], "extracted": 0, "extracted_matched": 0,
          "source_checked": 0, "source_wrong": [], "name_distortion": [], "invalid": False}

    def cat(name, ok, label):
        c = sc["by_cat"].setdefault(name, [0, 0])
        c[1] += 1
        sc["recall_max"] += 1
        if ok:
            c[0] += 1
            sc["recall_hit"] += 1
        else:
            sc["missing"].append(label)

    warns = (result or {}).get("_figyelmeztetesek", []) if result else []
    if result is None or any("pótolva: tunetek" in w for w in warns):
        sc["invalid"] = True

    result = result or {}
    syms = [s for s in result.get("tunetek", []) if isinstance(s, dict) and str(s.get("tunet") or "").strip()]
    pos = [s for s in syms if s.get("jelen_van") is True]
    neg = [s for s in syms if s.get("jelen_van") is False]
    used_pos, used_neg = set(), set()

    # --- felidézés -------------------------------------------------------------
    for name, stems in case["present"]:
        hits = [i for i, s in enumerate(pos) if matches(sym_text(s), stems)]
        cat("jelen lévő tünet", bool(hits), name)
        used_pos.update(hits)
        for i in hits[:1]:
            sc["source_checked"] += 1
            if not source_ok(pos[i], stems, case):
                sc["source_wrong"].append(f"{name}: {pos[i].get('forras_turn_ids')}")
        if any(matches(s.get("tunet", ""), stems) for s in neg):
            sc["false_negation"].append(f"jelen lévő tünet tagadottként: {name}")
            used_neg.update(i for i, s in enumerate(neg) if matches(s.get("tunet", ""), stems))

    for name, stems in case["absent"]:
        as_pos = [i for i, s in enumerate(pos) if matches(s.get("tunet", ""), stems)]
        if as_pos:
            sc["severe"].append(f"tagadott tünet jelenlévőként: {name}")
            used_pos.update(as_pos)
        hits = [i for i, s in enumerate(neg) if matches(s.get("tunet", ""), stems)]
        cat("tagadás", bool(hits) and not as_pos, f"tagadás: {name}")
        used_neg.update(hits)
        for i in hits[:1]:
            sc["source_checked"] += 1
            if not source_ok(neg[i], stems, case):
                sc["source_wrong"].append(f"tagadás {name}: {neg[i].get('forras_turn_ids')}")

    for name, stems in case.get("not_denied", []):
        hits = [i for i, s in enumerate(neg) if matches(s.get("tunet", ""), stems)]
        if hits:
            sc["false_negation"].append(f"bizonytalan válasz tagadottként: {name}")
            used_neg.update(hits)
        # jelenlévőként rögzítve elfogadható (bizonytalan), nem többlet
        used_pos.update(i for i, s in enumerate(pos) if matches(s.get("tunet", ""), stems))

    full = lambda s: sym_text(s, ("tunet", "lokalizacio", "kezdet_idotartam", "jelleg_sulyossag"))
    for name, sym_stems, detail_stems in case["details"]:
        hits = [s for s in pos if matches(sym_text(s), sym_stems)]
        cat("részlet", any(matches(full(s), detail_stems) for s in hits), f"részlet: {name}")

    lists = {f: real_items(result.get(f)) for f in ("gyogyszerek", "allergiak", "elozmenyek")}
    for field, key, label in (("gyogyszerek", "meds", "gyógyszer"), ("allergiak", "allergies", "allergia"),
                              ("elozmenyek", "history", "előzmény")):
        for name, stems in case.get(key, []):
            m = matches if field == "elozmenyek" else name_match
            cat(label, any(m(x, stems) for x in lists[field]), f"{label}: {name}")
    for flag, field, label in (("no_meds", "gyogyszerek", "gyógyszer"), ("no_allergies", "allergiak", "allergia")):
        if case.get(flag):
            cat(f"üres {label}lista", not lists[field], f"üres {label}lista")

    # --- precizitás és többlet -------------------------------------------------
    gold_sym = [st for _, st in case["present"] + case["absent"] + case.get("not_denied", [])]
    for i, s in enumerate(pos):
        sc["extracted"] += 1
        if i in used_pos or any(matches(sym_text(s), st) for st in gold_sym):
            sc["extracted_matched"] += 1
        else:
            (sc["extra_spoken"] if spoken(s["tunet"], case) else sc["extra_unspoken"]).append(f"tünet: {s['tunet']}")
    for i, s in enumerate(neg):
        sc["extracted"] += 1
        if i in used_neg:
            sc["extracted_matched"] += 1
        else:
            # tagadottként rögzített, nem elvárt tünet: ha a beteg tagadta, csak többlet
            (sc["extra_spoken"] if spoken(s["tunet"], case, roles=("beteg", "hozzátartozó", "orvos"))
             else sc["extra_unspoken"]).append(f"tagadott tünet: {s['tunet']}")

    gold = {"gyogyszerek": case.get("meds", []), "allergiak": case.get("allergies", []),
            "elozmenyek": case.get("history", [])}
    other = {"gyogyszerek": "allergiak", "allergiak": "gyogyszerek"}
    for field, label in (("gyogyszerek", "gyógyszer"), ("allergiak", "allergia")):
        for x in lists[field]:
            sc["extracted"] += 1
            if any(name_match(x, st) for _, st in gold[field]):
                sc["extracted_matched"] += 1
            elif any(name_match(x, st) for _, st in gold[other[field]]):
                sc["severe"].append(f"felcserélt lista: '{x}' a(z) {label} között")
            elif any(distorted(x, st) for _, st in gold[field]):
                sc["name_distortion"].append(f"{label}: {x}")
            elif spoken(x, case):
                sc["extra_spoken"].append(f"{label}: {x}")
            else:
                sc["severe"].append(f"kitalált {label}: '{x}'")
    for x in lists["elozmenyek"]:
        if not any(matches(x, st) for _, st in gold["elozmenyek"]):
            (sc["extra_spoken"] if spoken(x, case) else sc["extra_unspoken"]).append(f"előzmény: {x}")

    sc["guard_warnings"] = warns
    sc["guard_fabricated"] = sum(1 for w in warns if w.startswith(("KITALÁLT", "ÁTVETT")))
    sc["guard_wrong_source"] = sum(1 for w in warns if w.startswith("ROSSZ FORRÁS"))
    sc["guard_name"] = sum(1 for w in warns if w.startswith("NÉV NEM EGYEZIK"))
    return sc


# ---------------------------------------------------------------------------
# FUTTATÁS
# ---------------------------------------------------------------------------
def patch_think_off():
    """A qwen3.5 gondolkodó modell: a termék (docproc) think=False-szal hívja, a prototípus
    _call_ollama-ja ezt nem küldi. Az összevethetőségért itt kikapcsoljuk (csak qwen-nél)."""
    import beszed_pipeline as bp
    orig = bp.requests.post

    def post(url, json=None, **kw):
        if isinstance(json, dict) and str(json.get("model", "")).startswith("qwen"):
            json = {**json, "think": False}
        return orig(url, json=json, **kw)
    bp.requests.post = post


def run(models: list[str], runs: int, case_ids: list[str] | None) -> list[dict]:
    os.environ.setdefault("LLM_DEADLINE", "900")  # a 27B ~3 perc/hívás; a 120 s-os alap elvágná
    import beszed_pipeline as bp
    # a közös .env-ben alapcím áll (a docproc így várja) — ugyanaz az igazítás, mint a voice_config.py-ban
    base = bp.OLLAMA_URL.rstrip("/")
    bp.OLLAMA_URL = base if base.endswith("/api/chat") else base + "/api/chat"
    patch_think_off()
    cases = [c for c in CASES if not case_ids or c["id"] in case_ids]
    records = []
    for model in models:
        first = True
        for run_no in range(1, runs + 1):
            for case in cases:
                chat = [{"id": i, "role": r, "text": t} for i, (r, t) in enumerate(case["dialog"])]
                t0 = time.perf_counter()
                rec = {"model": model, "run": run_no, "case": case["id"], "first_call": first}
                try:
                    rec["result"] = bp.extract(chat, "sbo", model=model, keep_loaded=True)
                except Exception as e:  # időtúllépés, érvénytelen JSON, Ollama-hiba
                    rec["result"], rec["error"] = None, f"{type(e).__name__}: {e}"
                rec["seconds"] = round(time.perf_counter() - t0, 2)
                first = False
                records.append(rec)
                print(f"{model:<16} kör {run_no} {case['id']:<9} {rec['seconds']:7.1f} s"
                      f"{'  HIBA: ' + rec['error'][:80] if 'error' in rec else ''}", flush=True)
        try:
            bp.requests.post(bp.OLLAMA_URL.replace("/api/chat", "/api/generate"),
                             json={"model": model, "keep_alive": 0}, timeout=30)
        except Exception:
            pass
    return records


def pct(a, b):
    return f"{100 * a / b:.1f}%" if b else "–"


def report(records: list[dict]) -> str:
    out = []
    models = list(dict.fromkeys(r["model"] for r in records))
    n_cases = len({r["case"] for r in records})
    n_runs = len({r["run"] for r in records})
    out.append(f"# Strukturált kinyerés — {n_cases} eset × {n_runs} futás modellenként\n")
    out.append("| Modell | Felidézés | Precizitás | Sikertelen futás | Súlyos hiba | Téves tagadás | "
               "Többlet (nem hangzott el) | Kitalált részlet (őr törölte) | Névtorzítás | Rossz forrás | "
               "Idő (medián) | Első hívás |")
    out.append("|---|---|---|---|---|---|---|---|---|---|---|---|")
    detail = []
    for m in models:
        rr = [r for r in records if r["model"] == m]
        for r in rr:
            r["score"] = score(r.get("result"), CASE_BY_ID[r["case"]])
        S = lambda k: sum(r["score"][k] if isinstance(r["score"][k], int) else len(r["score"][k]) for r in rr)
        failed = sum(1 for r in rr if r["score"]["invalid"] or "error" in r)
        times = [r["seconds"] for r in rr if not r["first_call"]] or [r["seconds"] for r in rr]
        first = [r["seconds"] for r in rr if r["first_call"]]
        per_run = []
        for k in sorted({r["run"] for r in rr}):
            x = [r for r in rr if r["run"] == k]
            per_run.append(100 * sum(r["score"]["recall_hit"] for r in x) / sum(r["score"]["recall_max"] for r in x))
        rng = f" ({min(per_run):.0f}–{max(per_run):.0f}%)" if len(per_run) > 1 else ""
        out.append(f"| {m} | {S('recall_hit')}/{S('recall_max')} = {pct(S('recall_hit'), S('recall_max'))}{rng} | "
                   f"{S('extracted_matched')}/{S('extracted')} = {pct(S('extracted_matched'), S('extracted'))} | "
                   f"{failed}/{len(rr)} | {S('severe')} | {S('false_negation')} | {S('extra_unspoken')} | {S('guard_fabricated')} | {S('name_distortion')} | "
                   f"{S('source_wrong')}/{S('source_checked')} | "
                   f"{statistics.median(times):.1f} s | {first[0]:.1f} s |" if times else "| … |")
        cats = {}
        for r in rr:
            for c, (h, t) in r["score"]["by_cat"].items():
                cats.setdefault(c, [0, 0])
                cats[c][0] += h
                cats[c][1] += t
        detail.append(f"\n## {m}\n\nKategóriánként: " +
                      "; ".join(f"{c} {h}/{t}" for c, (h, t) in cats.items()))
        detail.append(f"\nA pipeline saját őrei: kitalált/átvett részlet törölve {S('guard_fabricated')}, "
                      f"ROSSZ FORRÁS jelzés {S('guard_wrong_source')}, névjelzés {S('guard_name')}.\n")
        for r in rr:
            s = r["score"]
            items = ([f"SIKERTELEN ({r.get('error', 'üres / érvénytelen kimenet')})"] if s["invalid"] or "error" in r else []) \
                + [f"SÚLYOS: {x}" for x in s["severe"]] + [f"téves tagadás: {x}" for x in s["false_negation"]] \
                + [f"hiányzik: {x}" for x in s["missing"]] + [f"rossz forrás: {x}" for x in s["source_wrong"]] \
                + [f"névtorzítás: {x}" for x in s["name_distortion"]] \
                + [f"többlet, nem hangzott el (kézzel nézd át): {x}" for x in s["extra_unspoken"]] \
                + [f"többlet, elhangzott: {x}" for x in s["extra_spoken"]]
            detail.append(f"- {r['run']}. kör, {r['case']} ({r['seconds']:.1f} s, "
                          f"{s['recall_hit']}/{s['recall_max']}): " + ("; ".join(items) or "hibátlan"))
    out.append("\nFelidézés: az elvárt elemek aránya (tünet, tagadás, részlet, gyógyszer, allergia, előzmény, "
               "üres lista ahol nincs); zárójelben a futásonkénti tartomány. Precizitás: a kinyert tünetek, "
               "gyógyszerek, allergiák közül az elvárttal egyezők aránya (az előzmény-többlet nem számít bele). "
               "Súlyos hiba: tagadott tünet jelenlévőként, kitalált vagy felcserélt gyógyszer/allergia. "
               "Téves tagadás: jelen lévő vagy meg nem válaszolt tünet tagadottként. Rossz forrás: a "
               "hivatkozott megszólalásban és az előtte álló kérdésben nincs ott az elem (az ellenőrzött, "
               "megtalált elemek közül). Névtorzítás: a gyógyszer / allergia neve egy elvárt névhez hasonló, de "
               "eltér (az ékezet nem számít); ilyenkor az elem hiányzónak számít. Kitalált részlet: a modell olyan "
               "időpontot / helyet / jelleget írt, amely nem hangzott el, vagy egy másik tünettől vette át; a "
               "pipeline őre törölte, mielőtt a pontozás látta volna (a modell hibája, a rendszer kimenetében nincs). Az idő mediánja az első (modellbetöltő) hívás nélkül.")
    return "\n".join(out + detail)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("models", nargs="*")
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--cases", help="vesszővel: " + ",".join(CASE_BY_ID))
    ap.add_argument("--rescore", nargs="+", help="nyers eredményfájl(ok) újrapontozása modellhívás nélkül")
    args = ap.parse_args()
    RESULTS.mkdir(exist_ok=True)
    stamp = f"{datetime.now():%Y%m%d_%H%M%S}"
    if args.rescore:
        records = []
        for f in args.rescore:
            data = json.loads(Path(f).read_text(encoding="utf-8"))
            for r in data:  # a régi llm_teszt-formátum is (ott nincs first_call, ok)
                r.setdefault("first_call", False)
                if r.get("ok") is False:
                    r["result"], r["error"] = None, r.get("error", "")
                records.append(r)
            # a régi fájlnál az első hívás a modellenként első rekord
            seen = set()
            for r in records:
                if r["model"] not in seen:
                    r["first_call"] = True
                    seen.add(r["model"])
    else:
        if not args.models:
            sys.exit("Adj meg legalább egy modellt (pl. medgemma:latest qwen3.5:9b medgemma:27b)")
        records = run(args.models, args.runs, args.cases.split(",") if args.cases else None)
        raw = RESULTS / f"extraction_raw_{stamp}.json"
        raw.write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\nNyers eredmény: {raw}")
    text = report(records)
    rep = RESULTS / f"extraction_report_{stamp}.md"
    rep.write_text(text, encoding="utf-8")
    print(text)
    print(f"\nJelentés: {rep}")


if __name__ == "__main__":
    main()
