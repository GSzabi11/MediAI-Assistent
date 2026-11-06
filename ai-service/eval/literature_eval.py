"""A Szakirodalom funkció mérése (IDK C2. táblázat, M6) — szintetikus betegeken, a termék kódjával.

A docproc modulokat (research.py, literature.py) közvetlenül hívja, a termék alapbeállításaival
(RESEARCH_LLM_MODEL a .env-ből, 3 forrás, 50 találat, 10 cikkes oldal), a Spring és az adatbázis nélkül:

  1. keresőkifejezés-javaslat a (jóváhagyottnak tekintett) betegösszefoglalóból — idő, a kifejezés
  2. keresés (PubMed, Cochrane, Europe PMC, iCite) — idő; --search-reps-szer ismételve (hálózatfüggő)
  3. AI-rangsor (előszűrés minden absztraktos cikkre + kiválasztás) — idő, idő/cikk, HIGH-arány
  4. részletes értékelés az AI-sorrend szerinti 1. oldalra (10 cikk) — idő/cikk, ellenőrizetlen szám
     az összefoglalóban (figyelmeztetés), sikertelen értékelés, előszűrés ↔ értékelés egyezése
  5. címkéző CSV a kézi relevancia-ítélethez; --score-labels-szel az egyezés (pontos, súlyozott kappa)

Kifelé csak a keresőkifejezés megy (a funkció egyetlen hálózati kivétele); a betegek kitaláltak.
A kimenő kapu (OutboundQueryGate) mérése Java-teszt: backend/…/OutboundQueryGateEvalTest.java.

Használat (a docproc környezetével; kell a .env MEDIAI_AI_TOKEN-je, mert a config betölti):
  python literature_eval.py                       # mind a 3 beteg
  python literature_eval.py --patients hf --search-reps 3
  python literature_eval.py --score-labels results\\literature_labels_….csv
"""
import argparse
import csv
import json
import statistics
import sys
import time
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
sys.path.insert(0, str(HERE.parent / "docproc"))

# Kitalált betegek: az orvos által jóváhagyott összefoglaló szövege (ez megy a helyi modellnek, ki nem).
PATIENTS = [
    {"id": "hf", "context": (
        "Szívelégtelenség csökkent ejekciós frakcióval (EF 30%), NYHA III. 2-es típusú diabetes mellitus, "
        "metforminnal; HbA1c 8,1%. Krónikus vesebetegség, eGFR 48 ml/perc/1,73 m². Jelenlegi kezelés: "
        "ramipril, bisoprolol, spironolakton, furoszemid. Nyitott kérdés: SGLT2-gátló bevezetése.")},
    {"id": "af", "context": (
        "Paroxizmális pitvarfibrilláció, CHA2DS2-VASc 4. Krónikus vesebetegség 3b stádium "
        "(eGFR 35 ml/perc/1,73 m²). Hypertonia, korábbi TIA. Jelenleg acenokumarolt szed, az INR "
        "ingadozó. Nyitott kérdés: váltás direkt orális antikoagulánsra, és ennek adagolása vesebetegségben.")},
    {"id": "copd", "context": (
        "Súlyos COPD (GOLD 3), az elmúlt évben két, kórházi kezelést igénylő exacerbáció. Eozinofilszám "
        "350/µl. Jelenlegi kezelés: tiotropium és olodaterol inhalációban. Dohányzott, 40 csomagév, 2 éve "
        "leszokott. Nyitott kérdés: inhalációs kortikoszteroid hozzáadása vagy roflumilaszt.")},
]
SOURCES = ["PUBMED", "COCHRANE", "EUROPE_PMC"]
MAX_RESULTS = 50  # ResearchService.DEFAULT_MAX_RESULTS
PAGE_SIZE = 10    # ResearchService.PAGE_SIZE
ORDER = {"HIGH": 0, "MEDIUM": 1, "LOW": 2}


def timed(fn, *a, **kw):
    t0 = time.perf_counter()
    out = fn(*a, **kw)
    return out, round(time.perf_counter() - t0, 2)


def ai_order(articles: list[dict], ranking: dict) -> list[dict]:
    """A termék sorrendje az értékelés előtt (ResearchArticleRepository.findPage): előszűrt relevancia,
    csoporton belül a kiemeltek (1–3), utána a forrás sorrendje; a besorolatlanok a végén."""
    rel = {s["articleId"]: s["relevance"] for s in ranking["screened"]}
    pick = {p["articleId"]: i for i, p in enumerate(ranking["picks"])}
    return sorted(articles, key=lambda a: (ORDER.get(rel.get(a["id"]), 3), pick.get(a["id"], 99), a["rank"]))


def run_patient(p: dict, search_reps: int) -> dict:
    import config
    import literature
    import research
    rec = {"patient": p["id"], "model": config.RESEARCH_LLM_MODEL, "context": p["context"]}
    print(f"\n=== {p['id']} ({config.RESEARCH_LLM_MODEL}) ===", flush=True)

    rec["query"], rec["query_seconds"] = timed(research.suggest_query, p["context"])
    print(f"kifejezés ({rec['query_seconds']} s): {rec['query']}", flush=True)
    if not rec["query"]:
        rec["error"] = "üres keresőkifejezés"
        return rec

    rec["search_seconds"] = []
    articles = []
    for _ in range(search_reps):
        (total, articles), sec = timed(literature.search, rec["query"], SOURCES, MAX_RESULTS, lambda m: None)
        rec["search_seconds"].append(sec)
        rec["total_found"] = total
    for a in articles:
        a["id"] = a["pmid"]
    rec["articles_n"] = len(articles)
    rec["with_abstract"] = sum(1 for a in articles if (a.get("abstract") or "").strip())
    print(f"keresés: {rec['search_seconds']} s, {total} találat, {len(articles)} letöltve, "
          f"{rec['with_abstract']} absztrakttal", flush=True)
    if not articles:
        return rec

    ranking, rec["rank_seconds"] = timed(research.rank, articles, p["context"], None, lambda m: None)
    rec["ranking"] = ranking
    print(f"rangsor: {rec['rank_seconds']} s, {len(ranking['screened'])} előszűrve, "
          f"{len(ranking['picks'])} kiemelt", flush=True)

    page = ai_order(articles, ranking)[:PAGE_SIZE]
    screened = {s["articleId"]: s["relevance"] for s in ranking["screened"]}
    rec["evaluations"] = []
    for a in page:
        ev = {"pmid": a["pmid"], "title": a.get("title"), "year": a.get("pubYear"),
              "screen": screened.get(a["id"]), "picked": any(x["articleId"] == a["id"] for x in ranking["picks"])}
        try:
            out, ev["seconds"] = timed(research.evaluate, a, p["context"], None)
            ev.update(relevance=out["relevance"], reason=out["relevanceReason"], summary=out["summary"],
                      warnings=out["warnings"])
        except Exception as e:  # absztrakt nélkül, hiányos válasz, Ollama-hiba
            ev.update(error=f"{type(e).__name__}: {e}", seconds=None)
        rec["evaluations"].append(ev)
        print(f"  {a['pmid']} {ev.get('seconds')} s előszűrés={ev['screen']} értékelés={ev.get('relevance')}"
              f"{' HIBA' if 'error' in ev else ''}", flush=True)
    rec["articles"] = [{k: a.get(k) for k in ("pmid", "title", "pubYear", "journal", "rank", "sources")}
                       for a in articles]
    return rec


def report(records: list[dict]) -> str:
    out = ["# Szakirodalom — mérés szintetikus betegeken\n",
           "| Beteg | Kifejezés (idő) | Keresés | Találat / letöltve / absztrakt | Rangsor | Előszűrés/cikk | "
           "HIGH előszűrés | Értékelés/cikk (medián) | Sikertelen értékelés | Ellenőrizetlen szám | "
           "Előszűrés = értékelés |",
           "|---|---|---|---|---|---|---|---|---|---|---|"]
    all_eval, all_screen_n, all_rank = [], 0, 0.0
    for r in records:
        if "articles_n" not in r or not r.get("ranking"):
            out.append(f"| {r['patient']} | {r.get('query_seconds')} s | – | {r.get('error', 'nincs találat')} |")
            continue
        sc = r["ranking"]["screened"]
        evs = r["evaluations"]
        ok = [e for e in evs if "error" not in e]
        secs = [e["seconds"] for e in ok]
        warn = sum(1 for e in ok if any("Ellenőrizetlen szám" in w for w in e["warnings"]))
        agree = sum(1 for e in ok if e["screen"] == e["relevance"])
        per = r["rank_seconds"] / max(len(sc), 1)
        all_eval += ok
        all_screen_n += len(sc)
        all_rank += r["rank_seconds"]
        high = sum(1 for s in sc if s["relevance"] == "HIGH")
        out.append(f"| {r['patient']} | {r['query_seconds']} s | {statistics.median(r['search_seconds']):.1f} s "
                   f"({min(r['search_seconds']):.1f}–{max(r['search_seconds']):.1f}) | {r['total_found']} / "
                   f"{r['articles_n']} / {r['with_abstract']} | {r['rank_seconds'] / 60:.1f} perc | ≈{per:.1f} s | "
                   f"{high}/{len(sc)} | {statistics.median(secs):.1f} s | {len(evs) - len(ok)}/{len(evs)} | "
                   f"{warn}/{len(ok)} | {agree}/{len(ok)} |" if secs else f"| {r['patient']} | … |")
    if all_eval:
        out.append(f"\nÖsszesen: {len(all_eval)} értékelés, medián {statistics.median(e['seconds'] for e in all_eval):.1f} s/cikk; "
                   f"előszűrés átlag {all_rank / max(all_screen_n, 1):.1f} s/cikk (a rangsor teljes ideje / előszűrt "
                   f"cikkek, a kiválasztással együtt).")
    out.append("\nA rangsor ideje az előszűrést és a kiválasztást is tartalmazza. Az „Ellenőrizetlen szám” az "
               "összefoglalóban álló, az absztraktban nem szereplő szám (a termék figyelmeztetése). Az "
               "„Előszűrés = értékelés” a két külön hívás belső egyezése, nem helyesség: a relevancia "
               "helyességéhez kézi címkézés kell (literature_labels_*.csv, --score-labels).")
    for r in records:
        out.append(f"\n## {r['patient']}\n\nKifejezés: `{r.get('query')}`\n")
        for e in r.get("evaluations", []):
            out.append(f"- PMID {e['pmid']} ({e.get('year')}) {'★ ' if e['picked'] else ''}{(e.get('title') or '')[:110]} — "
                       f"előszűrés {e['screen']}, értékelés {e.get('relevance', e.get('error'))}"
                       + (f"; {'; '.join(e['warnings'])}" if e.get("warnings") else ""))
    return "\n".join(out)


def write_labels(records: list[dict], path: Path):
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["beteg", "pmid", "ev", "cim", "modell_elozsures", "modell_ertekeles", "modell_indoklas",
                    "kezi_relevancia (HIGH/MEDIUM/LOW)", "megjegyzes"])
        for r in records:
            for e in r.get("evaluations", []):
                w.writerow([r["patient"], e["pmid"], e.get("year"), e.get("title"), e["screen"],
                            e.get("relevance", ""), e.get("reason", ""), "", ""])


def weighted_kappa(pairs: list[tuple[str, str]]) -> float:
    """Lineárisan súlyozott Cohen-kappa a három rendezett kategórián."""
    cats = ["LOW", "MEDIUM", "HIGH"]
    n = len(pairs)
    idx = {c: i for i, c in enumerate(cats)}
    w = lambda i, j: 1 - abs(i - j) / (len(cats) - 1)
    po = sum(w(idx[a], idx[b]) for a, b in pairs) / n
    pa = [sum(1 for a, _ in pairs if a == c) / n for c in cats]
    pb = [sum(1 for _, b in pairs if b == c) / n for c in cats]
    pe = sum(w(i, j) * pa[i] * pb[j] for i in range(3) for j in range(3))
    return (po - pe) / (1 - pe) if pe < 1 else 1.0


def score_labels(path: Path) -> str:
    rows = list(csv.DictReader(open(path, encoding="utf-8-sig"), delimiter=";"))
    key = next(k for k in rows[0] if k.startswith("kezi_relevancia"))
    out = [f"# Relevancia: a modell és a kézi címke egyezése ({path.name})\n"]
    for col, label in (("modell_ertekeles", "részletes értékelés"), ("modell_elozsures", "előszűrés")):
        pairs = [(r[col].strip().upper(), r[key].strip().upper()) for r in rows
                 if r[key].strip().upper() in ORDER and r[col].strip().upper() in ORDER]
        if not pairs:
            out.append(f"- {label}: nincs címkézett sor")
            continue
        exact = sum(a == b for a, b in pairs)
        over = sum(ORDER[a] < ORDER[b] for a, b in pairs)
        out.append(f"- {label}: {exact}/{len(pairs)} pontos egyezés ({100 * exact / len(pairs):.0f}%), "
                   f"súlyozott kappa {weighted_kappa(pairs):.2f}; a modell {over} esetben relevánsabbnak, "
                   f"{len(pairs) - exact - over} esetben kevésbé relevánsnak ítélte")
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--patients", help="vesszővel: " + ",".join(p["id"] for p in PATIENTS))
    ap.add_argument("--search-reps", type=int, default=3)
    ap.add_argument("--score-labels", type=Path)
    args = ap.parse_args()
    RESULTS.mkdir(exist_ok=True)
    if args.score_labels:
        print(score_labels(args.score_labels))
        return
    wanted = set(args.patients.split(",")) if args.patients else None
    records = [run_patient(p, args.search_reps) for p in PATIENTS if not wanted or p["id"] in wanted]
    stamp = f"{datetime.now():%Y%m%d_%H%M%S}"
    (RESULTS / f"literature_raw_{stamp}.json").write_text(json.dumps(records, ensure_ascii=False, indent=2),
                                                          encoding="utf-8")
    write_labels(records, RESULTS / f"literature_labels_{stamp}.csv")
    text = report(records)
    (RESULTS / f"literature_report_{stamp}.md").write_text(text, encoding="utf-8")
    print(text)
    print(f"\nNyers: results/literature_raw_{stamp}.json; címkéző: results/literature_labels_{stamp}.csv")


if __name__ == "__main__":
    main()
