"""Modellválasztás: klinikai asszisztens és klinikai összefoglaló (IDK 2. táblázat 3. sor) — kitalált betegen.

A termék kódját hívja (docproc/chat.py answer, docproc/summarize.py summarize) a Spring nélkül; a beteg tényei
a synthetic_patient.py-ban. Az asszisztensnél dokumentum-darab (RAG-találat) nincs, csak a strukturált tények
— a visszakeresést nem méri. Modellenként:

  asszisztens (4 kérdés): elvárt elemek említése, tilos állítás (normál labor kórosként; régi EF legutóbbiként),
  nem létező forrásra hivatkozás, forrás nélküli pont, ellenőrizetlen szám, hurok, idő / kérdés;
  összefoglaló: elvárt tartalom (szótő), a termék figyelmeztetései (ellentmondás, ellenőrizetlen szám,
  kimaradt kulcsvizsgálat, interakció a javaslatokban), ismétlés, idő.

Használat (a docproc környezetével):
  ..\\docproc\\.venv\\Scripts\\python clinical_eval.py medgemma:latest qwen3.5:9b medgemma:27b
"""
import argparse
import json
import re
import sys
import time
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
sys.path.insert(0, str(HERE.parent / "docproc"))
import synthetic_patient as sp  # noqa: E402

LETTER = "a-záéíóöőúüű0-9"


def has(text: str, stems: list[str]) -> bool:
    text = (text or "").lower()
    return any(re.search(rf"(?<![{LETTER}]){re.escape(s)}", text) for s in stems)


def set_model(model: str):
    import config
    config.LLM_MODEL = config.SUMMARY_LLM_MODEL = config.CHAT_LLM_MODEL = config.HISTORY_LLM_MODEL = model


def run(models: list[str], parts: set[str]) -> list[dict]:
    import chat
    import summarize
    records = []
    for model in models:
        set_model(model)
        if "chat" in parts:
            for q, gold, forbidden in sp.QUESTIONS:
                t0 = time.perf_counter()
                rec = {"model": model, "part": "chat", "question": q}
                try:
                    rec["out"] = chat.answer(q, [], sp.chat_facts(), [])
                except Exception as e:
                    rec["error"] = f"{type(e).__name__}: {e}"
                rec["seconds"] = round(time.perf_counter() - t0, 1)
                print(f"{model:<16} chat {rec['seconds']:6.1f} s  {q[:40]}{'  HIBA: ' + rec['error'] if 'error' in rec else ''}",
                      flush=True)
                records.append(rec)
        if "summary" in parts:
            t0 = time.perf_counter()
            rec = {"model": model, "part": "summary"}
            try:
                rec["out"] = summarize.summarize(sp.summary_facts())
            except Exception as e:
                rec["error"] = f"{type(e).__name__}: {e}"
            rec["seconds"] = round(time.perf_counter() - t0, 1)
            print(f"{model:<16} összefoglaló {rec['seconds']:6.1f} s{'  HIBA: ' + rec['error'] if 'error' in rec else ''}",
                  flush=True)
            records.append(rec)
    return records


def score_chat(rec: dict) -> dict:
    q = next(x for x in sp.QUESTIONS if x[0] == rec["question"])
    _, gold, forbidden = q
    out = rec.get("out") or {}
    points = out.get("points") or []
    text = " ".join(p["text"] for p in points)
    # tilos állítás: a mondat a tilos elemet kórosnak / legutóbbinak mondja
    bad = []
    for label, stems in forbidden:
        for p in points:
            if has(p["text"], stems) and not re.search(r"normál|tartományon belül|referencián belül|nem kóros", p["text"].lower()):
                bad.append(label)
                break
    warns = out.get("warnings") or []
    return {"hit": sum(1 for _, st in gold if has(text, st)), "total": len(gold),
            "missing": [l for l, st in gold if not has(text, st)], "forbidden": bad,
            "invalid_ref": sum(1 for w in warns if "nem létező forrásra" in w),
            "no_source": sum(1 for p in points if not p.get("sources")), "points": len(points),
            "unverified": sum(1 for w in warns if w.startswith("Ellenőrizetlen szám")),
            "cut": any("ismétlésbe" in w for w in warns), "error": rec.get("error")}


def score_summary(rec: dict) -> dict:
    out = rec.get("out") or {}
    s = out.get("summary") or ""
    warns = out.get("warnings") or []
    return {"hit": sum(1 for _, st in sp.SUMMARY_GOLD if has(s, st)), "total": len(sp.SUMMARY_GOLD),
            "missing": [l for l, st in sp.SUMMARY_GOLD if not has(s, st)],
            "contradiction": sum(1 for w in warns if w.startswith("Ellentmondás") or "nincs kóros eltérés" in w),
            "unverified": sum(1 for w in warns if w.startswith("Ellenőrizetlen szám")),
            "missing_exam": sum(1 for w in warns if "kulcsvizsgálat" in w.lower() or "nem említi" in w),
            # saját ellenőrzés: a termék figyelmeztetése csak az „interakció / kölcsönhatás” szót keresi
            "interaction_ok": has(" ".join(out.get("recommendations") or []), ["interakc", "kölcsönhat"])
            or all(has(" ".join(out.get("recommendations") or []), [x]) for x in ("ramipril", "spiron")),
            "cut": any("ismétlésbe" in w for w in warns), "recs": len(out.get("recommendations") or []),
            "error": rec.get("error"), "warnings": warns}


def report(records: list[dict]) -> str:
    out = ["# Klinikai asszisztens és összefoglaló — kitalált beteg (synthetic_patient.py)\n",
           "## Asszisztens (4 kérdés)\n",
           "| Modell | Elvárt elem említve | Tilos állítás | Nem létező forrás | Forrás nélküli pont | Ellenőrizetlen szám | Hurok | Idő / kérdés |",
           "|---|---|---|---|---|---|---|---|"]
    detail = []
    models = list(dict.fromkeys(r["model"] for r in records))
    for m in models:
        rr = [r for r in records if r["model"] == m and r["part"] == "chat"]
        if not rr:
            continue
        sc = [score_chat(r) for r in rr]
        S = lambda k: sum(x[k] if isinstance(x[k], int) else len(x[k]) for x in sc)
        secs = sorted(r["seconds"] for r in rr)
        out.append(f"| {m} | {S('hit')}/{S('total')} | {S('forbidden')} | {S('invalid_ref')} | {S('no_source')}/{S('points')} | "
                   f"{S('unverified')} | {sum(x['cut'] for x in sc)} | {secs[0]:.0f}–{secs[-1]:.0f} s |")
        for r, x in zip(rr, sc):
            detail.append(f"- {m} — „{r['question']}” ({r['seconds']:.0f} s): "
                          + ("; ".join(([f"HIBA: {x['error']}"] if x["error"] else []) + [f"hiányzik: {l}" for l in x["missing"]]
                                       + [f"TILOS: {l}" for l in x["forbidden"]]) or "minden elvárt elem, tilos állítás nélkül"))
            for p in (r.get("out") or {}).get("points", []):
                detail.append(f"    - {p['text'][:220]} [{len(p.get('sources') or [])} forrás]")
            detail += [f"    - figyelmeztetés: {w}" for w in (r.get("out") or {}).get("warnings", [])]
    out += ["\n## Klinikai összefoglaló\n",
            "| Modell | Elvárt tartalom | Ellentmondás (szabály szerint normál kórosként) | Ellenőrizetlen szám | "
            "Kimaradt kulcsvizsgálat | Súlyos interakció a javaslatokban | Hurok / ismétlés | Javaslatok | Idő |",
            "|---|---|---|---|---|---|---|---|---|"]
    for m in models:
        r = next((r for r in records if r["model"] == m and r["part"] == "summary"), None)
        if not r:
            continue
        x = score_summary(r)
        out.append(f"| {m} | {x['hit']}/{x['total']} | {x['contradiction']} | {x['unverified']} | {x['missing_exam']} | "
                   f"{'igen' if x['interaction_ok'] else 'nem'} | {'igen' if x['cut'] else 'nem'} | {x['recs']} | {r['seconds']:.0f} s |")
        detail.append(f"\n### {m} összefoglaló ({r['seconds']:.0f} s)" + (f" HIBA: {x['error']}" if x["error"] else ""))
        detail.append((r.get("out") or {}).get("summary", ""))
        detail += [f"- javaslat: {j}" for j in (r.get("out") or {}).get("recommendations", [])]
        detail += [f"- hiányzik: {l}" for l in x["missing"]] + [f"- figyelmeztetés: {w}" for w in x["warnings"]]
    out.append("\nElvárt elem: a válasz / összefoglaló szövegében szótővel szerepel. Tilos állítás: a normál laborértéket "
               "nem normálként, ill. a márciusi 32%-os EF-et legutóbbiként említi (a pont szövege alapján, automatikusan "
               "— a részleteknél kézzel ellenőrizhető). A többi oszlop a termék saját determinisztikus figyelmeztetése.")
    return "\n".join(out + ["\n## Részletek\n"] + detail)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("models", nargs="*", default=["medgemma:latest"])
    ap.add_argument("--parts", default="chat,summary")
    ap.add_argument("--rescore", type=Path)
    args = ap.parse_args()
    RESULTS.mkdir(exist_ok=True)
    stamp = f"{datetime.now():%Y%m%d_%H%M%S}"
    if args.rescore:
        records = json.loads(args.rescore.read_text(encoding="utf-8"))
    else:
        records = run(args.models, set(args.parts.split(",")))
        (RESULTS / f"clinical_raw_{stamp}.json").write_text(json.dumps(records, ensure_ascii=False, indent=2),
                                                            encoding="utf-8")
    text = report(records)
    (RESULTS / f"clinical_report_{stamp}.md").write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
