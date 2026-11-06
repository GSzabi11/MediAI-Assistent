"""Kórtörténet mérése (IDK 2. táblázat 7. sor) — kitalált beteg 4 dokumentumán, aranystandarddal.

A termék kódját hívja (docproc/history.py: make_digest dokumentumonként, majd build_timeline és
build_diagnoses), a Spring nélkül. A kórtörténet összefoglalója ugyanaz, mint a Klinikai státuszé
(summarize.write_summary) — azt a clinical_eval.py méri, itt nem ismételjük.

Mérőszámok modellenként: diagnózis felidézése (szótő), helyes BNO-kód (a leletben álló kód), a modell által
adott, de a leletben nem szereplő kód (az őr kiveszi — a modell hibája), dátumos korábbi esemény felidézése
(helyes évvel), a cím a leletből (különben az őr pótcímet ad), a beteg maszkjának kiírása a modell szövegében,
idő / dokumentum.

Használat (a docproc környezetével):
  ..\\docproc\\.venv\\Scripts\\python history_eval.py medgemma:latest medgemma:27b
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


def run(models: list[str]) -> list[dict]:
    import config
    import history
    records = []
    for model in models:
        config.LLM_MODEL = config.HISTORY_LLM_MODEL = model
        digests = {}
        for doc in sp.DOCUMENTS:
            t0 = time.perf_counter()
            rec = {"model": model, "doc": doc["id"]}
            try:
                rec["digest"] = history.make_digest(doc)
                digests[doc["id"]] = rec["digest"]
            except Exception as e:
                rec["error"] = f"{type(e).__name__}: {e}"
            rec["seconds"] = round(time.perf_counter() - t0, 1)
            print(f"{model:<16} dok {doc['id']} {rec['seconds']:6.1f} s{'  HIBA: ' + rec['error'] if 'error' in rec else ''}",
                  flush=True)
            records.append(rec)
        docs = [d for d in sp.DOCUMENTS if d["id"] in digests]
        records.append({"model": model, "doc": None,
                        "timeline": history.build_timeline(docs, digests, []) if docs else [],
                        "diagnoses": history.build_diagnoses(docs, digests) if docs else []})
    return records


def report(records: list[dict]) -> str:
    out = ["# Kórtörténet — kitalált beteg, 4 dokumentum (synthetic_patient.py)\n",
           "| Modell | Diagnózis | Helyes BNO | Kitalált BNO (őr kivette) | Korábbi esemény (helyes év) | Cím a leletből | "
           "Idézet nem egyezett (őr) | Többlet diagnózis | „[beteg]” a szövegben | Sikertelen kivonat | Idő / dokumentum |",
           "|---|---|---|---|---|---|---|---|---|---|---|"]
    detail = []
    for m in dict.fromkeys(r["model"] for r in records):
        rr = [r for r in records if r["model"] == m and r["doc"] is not None]
        dx = dx_n = bno = ev = ev_n = fake_bno = title_ok = quote_bad = mask = failed = extra = 0
        for r in rr:
            gold = sp.HISTORY_GOLD[r["doc"]]
            dg = r.get("digest")
            dx_n += len(gold["diagnoses"])
            ev_n += len(gold["events"])
            if not dg:
                failed += 1
                detail.append(f"- {m} dok {r['doc']}: HIBA {r.get('error')}")
                continue
            notes = []
            for label, stems, code in gold["diagnoses"]:
                hit = [d for d in dg["diagnoses"] if has(d["name"], stems)]
                dx += bool(hit)
                ok_code = any((d.get("bnoCode") or "").replace(".", "") == code.replace(".", "") for d in hit)
                bno += ok_code
                if not hit:
                    notes.append(f"hiányzó diagnózis: {label}")
                elif not ok_code:
                    notes.append(f"BNO hiányzik: {label} ({[d.get('bnoCode') for d in hit]})")
            all_stems = [st for _, st, _ in gold["diagnoses"]]
            extras = [d["name"] for d in dg["diagnoses"] if not any(has(d["name"], st) for st in all_stems)]
            extra += len(extras)
            notes += [f"többlet diagnózis: {x}" for x in extras]
            for year, stems in gold["events"]:
                e = [x for x in dg["events"] if x["date"].startswith(year) and (has(x["title"], stems) or has(x["quote"], stems))]
                ev += bool(e)
                if not e:
                    notes.append(f"hiányzó esemény: {year} {stems[0]}")
            w = dg.get("warnings") or []
            fake_bno += sum(1 for x in w if x.startswith("BNO-kód"))
            quote_bad += sum(1 for x in w if "idézet" in x.lower() or "nem támasztható alá" in x)
            title_ok += not any("címe" in x for x in w)
            text = " ".join([dg["title"], dg["summary"]] + [d["name"] for d in dg["diagnoses"]] + [e["title"] for e in dg["events"]])
            mask += "[beteg]" in text
            detail.append(f"- {m} dok {r['doc']} ({r['seconds']:.0f} s) — cím: „{dg['title']}”; diagnózisok: "
                          + ", ".join(f"{d['name']} ({d.get('bnoCode')})" for d in dg["diagnoses"])
                          + "; események: " + ", ".join(f"{e['date']} {e['title']}" for e in dg["events"])
                          + ("; " + "; ".join(notes) if notes else ""))
            detail += [f"    - őr: {x}" for x in w]
            detail.append(f"    - összefoglaló: {dg['summary']}")
        secs = [r["seconds"] for r in rr]
        out.append(f"| {m} | {dx}/{dx_n} | {bno}/{dx_n} | {fake_bno} | {ev}/{ev_n} | {title_ok}/{len(rr) - failed} | {quote_bad} | {extra} | "
                   f"{mask} | {failed}/{len(rr)} | {min(secs):.0f}–{max(secs):.0f} s (össz. {sum(secs) / 60:.1f} perc) |")
        agg = next((r for r in records if r["model"] == m and r["doc"] is None), None)
        if agg:
            detail.append(f"- {m} idővonal: {len(agg['timeline'])} elem, ebből jelölt (ellenőrizendő): "
                          f"{sum(1 for t in agg['timeline'] if t.get('flag'))}; összefésült diagnózisok: "
                          + ", ".join(f"{d['name']} ({d.get('bnoCode')}, {d['date']})" for d in agg["diagnoses"]))
    out.append("\nA diagnózis szótővel egyezik az aranystandarddal; a helyes BNO a leletben álló kód. A többlet diagnózis az aranystandardban nem szereplő, a modell által diagnózisként felvett tétel (kézzel átnézendő: lehet leletleírás is). A „kitalált BNO” a "
               "modell által adott, a leletben nem szereplő kód, amelyet a determinisztikus ellenőrzés kivett. Az "
               "esemény akkor helyes, ha a dátum éve egyezik és a cím / idézet a várt fogalmat tartalmazza.")
    return "\n".join(out + ["\n## Részletek\n"] + detail)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("models", nargs="*", default=["medgemma:latest"])
    ap.add_argument("--rescore", type=Path)
    args = ap.parse_args()
    RESULTS.mkdir(exist_ok=True)
    stamp = f"{datetime.now():%Y%m%d_%H%M%S}"
    if args.rescore:
        records = json.loads(args.rescore.read_text(encoding="utf-8"))
    else:
        records = run(args.models)
        (RESULTS / f"history_raw_{stamp}.json").write_text(json.dumps(records, ensure_ascii=False, indent=2),
                                                           encoding="utf-8")
    text = report(records)
    (RESULTS / f"history_report_{stamp}.md").write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
