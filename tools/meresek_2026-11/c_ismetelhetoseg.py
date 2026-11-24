"""C) Ismételhetőség — a 2026-10-27-i 4. (hangátnézés), 2. (PDF-laborkinyerés) és 8. (hat csoport) mérés seedekkel.

A meglévő mérőszkriptek (ai-service/eval) aranystandardját és pontozását használja, a termék kódját hívja.
  review   — voice/review.py, VOICE_REVIEW_SEED-del (review.REVIEW_SEED), SEEDS × --runs futás modellenként
  lab      — docproc PDF-laborkinyerés (config.LLM_SEED), 3 szintetikus lelet, seedenként egyszer
  findings — voice/findings.py (findings._OPTIONS["seed"]), 3 kitalált párbeszéd, seedenként egyszer
  report   — a nyers fájlokból összesítés (eredmenyek/c_*.json → eredmenyek/c_osszesites.json)

Futtatás (a repó gyökeréből):
  ai-service\\voice\\ai_env\\Scripts\\python tools\\meresek_2026-11\\c_ismetelhetoseg.py review --models medgemma:latest --runs 3
  ai-service\\voice\\ai_env\\Scripts\\python tools\\meresek_2026-11\\c_ismetelhetoseg.py review --models medgemma:27b --runs 1
  ai-service\\docproc\\.venv\\Scripts\\python tools\\meresek_2026-11\\c_ismetelhetoseg.py lab
  ai-service\\voice\\ai_env\\Scripts\\python tools\\meresek_2026-11\\c_ismetelhetoseg.py findings
  python tools\\meresek_2026-11\\c_ismetelhetoseg.py report
"""
import argparse
import copy
import itertools
import json
import os
import sys
import time
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
EVAL = ROOT / "ai-service" / "eval"
OUT = HERE / "eredmenyek"
SEEDS = [1, 42, 1234]
sys.path.insert(0, str(EVAL))


def _ollama(bp):
    base = os.environ.get("OLLAMA_URL", "http://localhost:11434").rstrip("/")
    bp.OLLAMA_URL = base if base.endswith("/api/chat") else base + "/api/chat"


def run_review(models, runs):
    sys.path.insert(0, str(ROOT / "ai-service" / "voice"))
    import beszed_pipeline as bp
    import review
    import review_eval as ev
    _ollama(bp)
    bp.LLM_DEADLINE = max(bp.LLM_DEADLINE, 900)
    for model in models:
        recs = []
        review.REVIEW_MODEL = model
        for seed in SEEDS:
            review.REVIEW_SEED = seed
            for run in range(runs):
                rec = {"model": model, "seed": seed, "run": run}
                for mode, make in (("sbo", ev.chat_sbo), ("echo", ev.chat_echo)):
                    t0 = time.perf_counter()
                    out, used, warnings = review.review(copy.deepcopy(make()), mode)
                    rec[mode] = {"seconds": round(time.perf_counter() - t0, 1), "ran": used is not None,
                                 "out": [{"id": t["id"], "role": t["role"], "text": t["text"],
                                          "review_note": t.get("review_note")} for t in out],
                                 "warnings": warnings}
                print(f"{model} seed={seed} run={run}: {rec['sbo']['seconds']} s / {rec['echo']['seconds']} s", flush=True)
                recs.append(rec)
        name = f"c_review_{model.replace(':', '_')}.json"
        OUT.mkdir(exist_ok=True)
        (OUT / name).write_text(json.dumps(recs, ensure_ascii=False, indent=1), encoding="utf-8")


def run_lab():
    import lab_eval as ev
    sys.path.insert(0, str(ROOT / "ai-service" / "docproc"))
    os.environ["DOC_LLM_MODEL"] = "medgemma:latest"
    import config
    recs = []
    for seed in SEEDS:
        config.LLM_SEED = seed
        for r in ev.run("medgemma:latest", list(ev.PANELS)):
            r["seed"] = seed
            recs.append(r)
    OUT.mkdir(exist_ok=True)
    (OUT / "c_lab_medgemma_latest.json").write_text(json.dumps(recs, ensure_ascii=False, indent=1, default=str),
                                                    encoding="utf-8")


def run_findings():
    sys.path.insert(0, str(ROOT / "ai-service" / "voice"))
    import findings
    import findings_eval as ev
    recs = []
    for seed in SEEDS:
        findings._OPTIONS["seed"] = seed
        findings.SEED = seed
        for r in ev.run(["medgemma:latest"]):
            r["seed"] = seed
            recs.append(r)
    OUT.mkdir(exist_ok=True)
    (OUT / "c_findings_medgemma_latest.json").write_text(json.dumps(recs, ensure_ascii=False, indent=1),
                                                         encoding="utf-8")


# ---------------------------------------------------------------------------
def _rng(xs):
    xs = list(xs)
    return {"mean": round(sum(xs) / len(xs), 2), "min": min(xs), "max": max(xs), "n": len(xs)} if xs else {}


def _pairs(recs, sig):
    """Páronkénti egyezés: (azonos seed, különböző seed) → [azonos-e a kimenet aláírása]."""
    same, diff = [], []
    for a, b in itertools.combinations(recs, 2):
        (same if a["seed"] == b["seed"] else diff).append(sig(a) == sig(b))
    f = lambda v: {"pairs": len(v), "identical": sum(v), "rate": round(sum(v) / len(v), 3) if v else None}
    return {"same_seed": f(same), "across_seeds": f(diff)}


def _jaccard(recs, sig):
    same, diff = [], []
    for a, b in itertools.combinations(recs, 2):
        x, y = sig(a), sig(b)
        j = len(x & y) / len(x | y) if x | y else 1.0
        (same if a["seed"] == b["seed"] else diff).append(j)
    return {"same_seed": round(sum(same) / len(same), 3) if same else None,
            "across_seeds": round(sum(diff) / len(diff), 3) if diff else None}


def report():
    import review_eval as rev
    summary = {"date": datetime.now().isoformat(timespec="seconds"), "seeds": SEEDS}
    for f in sorted(OUT.glob("c_review_*.json")):
        recs = json.loads(f.read_text(encoding="utf-8"))
        model = recs[0]["model"]
        rows = []
        for r in recs:
            a, b = rev.score_sbo(r["sbo"]["out"]), rev.score_echo(r["echo"]["out"])
            changes = frozenset([("t", t["id"], t["text"]) for t in r["sbo"]["out"]] +
                                [("r", t["id"], t["role"]) for t in r["sbo"]["out"]] +
                                [("n", t["id"], t.get("review_note") or "") for t in r["echo"]["out"]])
            rows.append({"seed": r["seed"], "run": r["run"], "fixed": len(a["fixed"]), "wrong_fix": len(a["wrong_fix"]),
                         "broken": len(a["broken"]), "speaker_ok": len(a["speaker_ok"]),
                         "speaker_wrong": len(a["speaker_wrong"]), "echo_flagged": len(b["flagged_ok"]),
                         "echo_false": len(b["false_flag"]),
                         "correct_total": len(a["fixed"]) + len(a["speaker_ok"]) + len(b["flagged_ok"]),
                         "seconds": r["sbo"]["seconds"] + r["echo"]["seconds"], "ran": r["sbo"]["ran"] and r["echo"]["ran"],
                         "_sig": changes})
        for row in rows:
            row["_set"] = frozenset(x for x in row["_sig"] if not (x[0] == "n" and not x[2]))
        summary[f"review {model}"] = {
            "runs": len(rows), "runs_per_seed": max(r["run"] for r in recs) + 1,
            "all_ran": all(r["ran"] for r in rows),
            **{k: _rng(r[k] for r in rows) for k in ("correct_total", "fixed", "wrong_fix", "broken", "speaker_ok",
                                                       "speaker_wrong", "echo_flagged", "echo_false", "seconds")},
            "identical_output": _pairs(rows, lambda r: r["_sig"]),
            "per_run": [{k: v for k, v in r.items() if not k.startswith("_")} for r in rows],
        }
    f = OUT / "c_lab_medgemma_latest.json"
    if f.exists():
        import lab_eval as lev
        recs = json.loads(f.read_text(encoding="utf-8"))
        per_seed = {}
        for r in recs:
            c = lev.compare(lev.gold_rows(lev.PANELS[r["panel"]]), r["rows"])
            d = per_seed.setdefault(r["seed"], {"recall": 0, "gold": 0, "extracted": 0, "wrong": 0, "boxes": 0,
                                                "seconds": 0, "_sig": []})
            d["recall"] += c["recall"]; d["gold"] += c["gold"]; d["extracted"] += c["extracted"]
            d["wrong"] += len(c["wrong_value"]); d["boxes"] += c["boxes"][0]; d["seconds"] += r["seconds"]
            d["_sig"].append(json.dumps(r["rows"], sort_keys=True, ensure_ascii=False, default=str))
        sigs = [tuple(d["_sig"]) for d in per_seed.values()]
        summary["lab medgemma:latest"] = {
            "per_seed": {s: {k: v for k, v in d.items() if not k.startswith("_")} for s, d in per_seed.items()},
            "recall": _rng(d["recall"] for d in per_seed.values()),
            "boxes": _rng(d["boxes"] for d in per_seed.values()),
            "rows_identical_across_seeds": len(set(sigs)) == 1,
            "panels_identical_across_seeds": sum(len({s[i] for s in sigs}) == 1 for i in range(len(sigs[0]))),
            "panels": len(sigs[0]),
        }
    f = OUT / "c_findings_medgemma_latest.json"
    if f.exists():
        import findings_eval as fev
        by_id = {c["id"]: c for c in fev.CASES}
        recs = json.loads(f.read_text(encoding="utf-8"))
        per_seed = {}
        for r in recs:
            s = fev.score(r["items"], by_id[r["case"]])
            d = per_seed.setdefault(r["seed"], {"hit": 0, "total": 0, "items": 0, "matched": 0, "seconds": 0, "_sig": []})
            d["hit"] += s["hit"]; d["total"] += s["total"]; d["items"] += len(r["items"])
            d["matched"] += s["matched_items"]; d["seconds"] += r["seconds"]
            d["_sig"].append(json.dumps(sorted((i["category"], i["text"]) for i in r["items"]), ensure_ascii=False))
        sigs = [tuple(d["_sig"]) for d in per_seed.values()]
        summary["findings medgemma:latest"] = {
            "per_seed": {s: {k: v for k, v in d.items() if not k.startswith("_")} for s, d in per_seed.items()},
            "recall": _rng(d["hit"] for d in per_seed.values()),
            "precision_matched": _rng(d["matched"] for d in per_seed.values()),
            "items": _rng(d["items"] for d in per_seed.values()),
            "identical_across_seeds": len(set(sigs)) == 1,
            "cases_identical_across_seeds": sum(len({s[i] for s in sigs}) == 1 for i in range(len(sigs[0]))),
            "cases": len(sigs[0]),
        }
    (OUT / "c_osszesites.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1, default=str),
                                           encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=1, default=str))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("what", choices=["review", "lab", "findings", "report"])
    ap.add_argument("--models", nargs="*", default=["medgemma:latest"])
    ap.add_argument("--runs", type=int, default=3)
    a = ap.parse_args()
    {"review": lambda: run_review(a.models, a.runs), "lab": run_lab, "findings": run_findings,
     "report": report}[a.what]()


if __name__ == "__main__":
    main()
