"""A felvétel hat csoportjának mérése (IDK 2. táblázat 8. sor) — kitalált SBO-párbeszédeken, aranystandarddal.

A termék kódját hívja (voice/findings.py: MedGemma-javaslat + determinisztikus ellenőrzés), a Spring
nélkül. Az átirat itt kész szöveg (nem hangfelvétel), így csak a csoportosítást méri, a beszédfelismerést nem.

Aranystandard elemenként: kategória (és az elfogadható másik kategória, ahol a besorolás vitatható, pl. a
troponin-kérés beutaló vagy javaslat), szótövek, és a megszólalás, ahol elhangzott. Mérőszámok: felidézés
(csoportonként is), precizitás, rossz csoport, tagadott tünet panaszként (súlyos), forrás (a hivatkozott
megszólalások között ott van-e az elvárt), BNO-kód, idő.

Használat (az ai-service/voice ai_env környezetével):
  ..\\voice\\ai_env\\Scripts\\python findings_eval.py medgemma:latest medgemma:27b
"""
import argparse
import json
import os
import re
import sys
import time
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
sys.path.insert(0, str(HERE.parent / "voice"))

D, P = "DOCTOR", "PATIENT"
CASES = [
    {"id": "mellkas", "dialog": [
        (D, "Jó napot, mi a panasza?"),
        (P, "Két órája szorít a mellkasom, és kisugárzik az állkapcsomba. Közben nagyon izzadok."),
        (D, "Nehezen kap levegőt?"),
        (P, "Igen, egy kicsit fulladok is."),
        (D, "Láza volt?"),
        (P, "Nem, láz nem volt."),
        (D, "Megmértem a vérnyomását, 165 per 95, a pulzusa 98. A szívhangok tiszták, zörej nincs. A tüdő felett vezikuláris légzés hallható."),
        (D, "Az EKG-n a II., III. és aVF elvezetésben ST-elevációt látok, ez alapján akut inferior szívinfarktus gyanúja áll fenn."),
        (D, "Most kap 300 milligramm aszpirint elrágva és 180 milligramm ticagrelort."),
        (D, "Azonnal átküldöm a kardiológiára, a katéteres laborba koronarográfiára."),
        (P, "Rendben. A vérnyomásgyógyszeremet szedjem tovább?"),
        (D, "A ramiprilt most ne vegye be, a kardiológián döntenek róla. Troponint is kérek."),
    ], "gold": [
        ("mellkasi szorító fájdalom", "COMPLAINT", (), ["mellkas", "szorít"], 1),
        ("kisugárzás az állkapocsba", "COMPLAINT", (), ["állkap", "kisugár"], 1),
        ("izzadás", "COMPLAINT", (), ["izzad"], 1),
        ("nehézlégzés", "COMPLAINT", (), ["fullad", "nehézlégz", "levegő", "dyspn"], 3),
        ("vérnyomás 165/95", "EXAM_FINDING", (), ["165", "vérnyomás"], 6),
        ("pulzus 98", "EXAM_FINDING", (), ["98", "pulzus"], 6),
        ("tiszta szívhangok", "EXAM_FINDING", (), ["szívhang"], 6),
        ("vezikuláris légzés", "EXAM_FINDING", (), ["vezikul"], 6),
        ("ST-eleváció az EKG-n", "EXAM_FINDING", ("DIAGNOSIS",), ["st-elev", "st elev", "elevá"], 7),
        ("inferior infarktus gyanúja", "DIAGNOSIS", (), ["infarktus", "szívinfarkt", "stemi", "infarct"], 7),
        ("aszpirin 300 mg", "MEDICATION", (), ["aszpirin", "aspirin", "acetilszalic"], 8),
        ("ticagrelor 180 mg", "MEDICATION", (), ["ticagrel", "tikagrel"], 8),
        ("kardiológia / koronarográfia", "REFERRAL", (), ["kardiológ", "koronarogr", "katéter"], 9),
        ("troponin", "REFERRAL", ("RECOMMENDATION", "EXAM_FINDING"), ["troponin"], 11),
        ("ramipril kihagyása", "RECOMMENDATION", ("MEDICATION",), ["ramipril"], 11),
    ], "negated": [("láz", ["láz"])]},
    {"id": "hasi", "dialog": [
        (D, "Jó napot, mi a panasza?"),
        (P, "Tegnap este óta fáj a hasam, most jobb oldalt lent a legrosszabb. Kétszer hánytam is."),
        (D, "Hasmenése volt?"),
        (P, "Nem, hasmenésem nincs."),
        (D, "Megnézem a hasát. A jobb alsó negyed nyomásérzékeny, a McBurney-pont érzékeny, enyhe izomvédekezés van. A testhőmérséklete 38,1 fok."),
        (D, "Ez alapján akut vakbélgyulladás gyanúja merül fel."),
        (D, "Kérek laborvizsgálatot, vérképet és CRP-t, és hasi ultrahangot."),
        (D, "Sebészeti konzíliumot is kérek."),
        (D, "Fájdalomra kap egy gramm metamizolt infúzióban."),
        (D, "Addig ne egyen és ne igyon semmit."),
        (P, "Értem."),
    ], "gold": [
        ("hasi fájdalom", "COMPLAINT", (), ["hasam", "hasfáj", "has fáj", "hasi"], 1),
        ("hányás", "COMPLAINT", (), ["hány"], 1),
        ("jobb alsó negyed nyomásérzékeny", "EXAM_FINDING", (), ["nyomásérz", "érzékeny", "mcburney"], 4),
        ("izomvédekezés", "EXAM_FINDING", (), ["izomvéd", "défense", "defense"], 4),
        ("testhőmérséklet 38,1", "EXAM_FINDING", ("COMPLAINT",), ["38", "hőmérsék"], 4),
        ("vakbélgyulladás gyanúja", "DIAGNOSIS", (), ["vakbél", "appendic"], 5),
        ("labor: vérkép, CRP", "REFERRAL", ("RECOMMENDATION",), ["labor", "vérkép", "crp"], 6),
        ("hasi ultrahang", "REFERRAL", ("RECOMMENDATION",), ["ultrahang", "uh"], 6),
        ("sebészeti konzílium", "REFERRAL", (), ["sebész"], 7),
        ("metamizol 1 g", "MEDICATION", (), ["metamizol", "algopyrin"], 8),
        ("ne egyen, ne igyon", "RECOMMENDATION", (), ["egyen", "igyon", "éhez", "per os"], 9),
    ], "negated": [("hasmenés", ["hasmen"])]},
    {"id": "copd", "dialog": [
        (D, "Jó napot, miért jött be?"),
        (P, "Három napja egyre nehezebben kapok levegőt, és sárgászöld köpetet köhögök fel."),
        (D, "Láza van?"),
        (P, "Tegnap 37,8 volt."),
        (D, "A tüdő felett mindkét oldalon kilégzési sípolás hallható, a szaturáció 91 százalék szobalevegőn, a légzésszám 24."),
        (D, "A mellkasröntgen-felvételen beszűrődés nem látszik."),
        (D, "Ez a COPD akut exacerbációja, BNO J44.1."),
        (D, "Öt napig napi 40 milligramm prednizolont írok fel, és amoxicillin-klavulánsavat naponta kétszer 875/125 milligrammot hét napig."),
        (D, "A salbutamol sprayt rohamban használja, akár négyóránként."),
        (D, "Egy hét múlva jöjjön vissza kontrollra a tüdőgondozóba."),
        (D, "Ha romlik a légzése vagy magas láza lesz, azonnal jöjjön vissza."),
        (P, "Rendben, köszönöm."),
    ], "gold": [
        ("nehézlégzés", "COMPLAINT", (), ["levegő", "nehézlégz", "dyspn", "fullad"], 1),
        ("köpet", "COMPLAINT", (), ["köpet", "köhög"], 1),
        ("hőemelkedés 37,8", "COMPLAINT", ("EXAM_FINDING",), ["37", "hőemelk"], 3),
        ("kilégzési sípolás", "EXAM_FINDING", (), ["sípol"], 4),
        ("szaturáció 91%", "EXAM_FINDING", (), ["szatur", "91"], 4),
        ("légzésszám 24", "EXAM_FINDING", (), ["légzésszám", "24"], 4),
        ("röntgen: nincs beszűrődés", "EXAM_FINDING", (), ["röntgen", "beszűr"], 5),
        ("COPD exacerbáció", "DIAGNOSIS", (), ["copd", "exacerb"], 6),
        ("prednizolon 40 mg", "MEDICATION", (), ["prednizol"], 7),
        ("amoxicillin-klavulánsav", "MEDICATION", (), ["amoxicillin", "klavulán", "augmentin"], 7),
        ("salbutamol rohamban", "MEDICATION", ("RECOMMENDATION",), ["salbutamol"], 8),
        ("kontroll egy hét múlva, tüdőgondozó", "RECOMMENDATION", ("REFERRAL",), ["kontroll", "tüdőgondoz"], 9),
        ("romlás esetén vissza", "RECOMMENDATION", (), ["romlik", "romlás"], 10),
    ], "negated": [], "bno": {"DIAGNOSIS": "J44.1"}},
]
LETTER = "a-záéíóöőúüű0-9"


def matches(text: str, stems: list[str]) -> bool:
    text = (text or "").lower()
    return any(re.search(rf"(?<![{LETTER}]){re.escape(s)}", text) for s in stems)


def score(items: list[dict], case: dict) -> dict:
    sc = {"by_cat": {}, "hit": 0, "total": 0, "missing": [], "wrong_group": [], "extra": [], "severe": [],
          "source_wrong": [], "matched_items": 0, "bno": None}
    used = set()
    for label, cat, alt, stems, turn in case["gold"]:
        c = sc["by_cat"].setdefault(cat, [0, 0])
        c[1] += 1
        sc["total"] += 1
        ok = [i for i, it in enumerate(items) if it["category"] in (cat, *alt) and matches(it["text"], stems)]
        if ok:
            c[0] += 1
            sc["hit"] += 1
            used.update(ok)
            if turn not in items[ok[0]]["turnIds"]:
                sc["source_wrong"].append(f"{label}: {items[ok[0]]['turnIds']} (elvárt {turn})")
        else:
            other = [i for i, it in enumerate(items) if matches(it["text"], stems)]
            if other:
                used.update(other)
                sc["wrong_group"].append(f"{label}: {items[other[0]]['category']} (elvárt {cat})")
            sc["missing"].append(label)
    for label, stems in case["negated"]:
        bad = [i for i, it in enumerate(items) if it["category"] == "COMPLAINT" and matches(it["text"], stems)
               and not re.search(r"\b(nincs|nem|tagad)", it["text"].lower())]
        if bad:
            sc["severe"].append(f"tagadott tünet panaszként: {label}")
            used.update(bad)
    sc["matched_items"] = len(used)
    sc["extra"] = [f"{it['category']}: {it['text']}" for i, it in enumerate(items) if i not in used]
    if case.get("bno"):
        got = [it.get("bnoCode") for it in items if it["category"] == "DIAGNOSIS"]
        sc["bno"] = f"{'helyes' if case['bno']['DIAGNOSIS'] in got else 'hiányzik'} ({got})"
    return sc


def run(models: list[str]) -> list[dict]:
    import beszed_pipeline as bp
    import findings
    import review
    base = os.environ.get("OLLAMA_URL", "http://localhost:11434").rstrip("/")
    bp.OLLAMA_URL = base if base.endswith("/api/chat") else base + "/api/chat"
    records = []
    for model in models:
        review.REVIEW_MODEL = model
        for case in CASES:
            utts = [{"turnId": i, "speaker": s, "text": t} for i, (s, t) in enumerate(case["dialog"])]
            t0 = time.perf_counter()
            rec = {"model": model, "case": case["id"]}
            try:
                out = findings.extract("sbo", utts)
                rec.update(items=out["items"], warnings=out["warnings"])
            except Exception as e:
                rec.update(items=[], warnings=[], error=f"{type(e).__name__}: {e}")
            rec["seconds"] = round(time.perf_counter() - t0, 1)
            print(f"{model:<16} {case['id']:<8} {rec['seconds']:6.1f} s  {len(rec['items'])} elem"
                  f"{'  HIBA: ' + rec['error'] if 'error' in rec else ''}", flush=True)
            records.append(rec)
    return records


def report(records: list[dict]) -> str:
    by_id = {c["id"]: c for c in CASES}
    out = ["# A felvétel hat csoportja — kitalált SBO-párbeszédek, aranystandard\n",
           "| Modell | Felidézés | Precizitás | Rossz csoport | Tagadott tünet panaszként | Rossz forrás | BNO | Idő (esetenként) |",
           "|---|---|---|---|---|---|---|---|"]
    detail, cats_out = [], []
    for m in dict.fromkeys(r["model"] for r in records):
        rr = [r for r in records if r["model"] == m]
        for r in rr:
            r["score"] = score(r["items"], by_id[r["case"]])
        S = lambda k: sum(r["score"][k] if isinstance(r["score"][k], int) else len(r["score"][k]) for r in rr)
        n_items = sum(len(r["items"]) for r in rr)
        bno = "; ".join(r["score"]["bno"] for r in rr if r["score"]["bno"]) or "–"
        out.append(f"| {m} | {S('hit')}/{S('total')} = {100 * S('hit') / S('total'):.0f}% | "
                   f"{S('matched_items')}/{n_items} | {S('wrong_group')} | {S('severe')} | {S('source_wrong')} | {bno} | "
                   f"{', '.join(f'{r['seconds']:.0f}' for r in rr)} s |")
        cats = {}
        for r in rr:
            for c, (h, t) in r["score"]["by_cat"].items():
                cats.setdefault(c, [0, 0])
                cats[c][0] += h
                cats[c][1] += t
        cats_out.append(f"- {m}: " + "; ".join(f"{c} {h}/{t}" for c, (h, t) in cats.items()))
        for r in rr:
            s = r["score"]
            detail.append(f"- {m} {r['case']} ({r['seconds']:.0f} s): " + ("; ".join(
                ([f"HIBA: {r['error']}"] if "error" in r else []) + [f"SÚLYOS: {x}" for x in s["severe"]]
                + [f"hiányzik: {x}" for x in s["missing"]] + [f"rossz csoport: {x}" for x in s["wrong_group"]]
                + [f"rossz forrás: {x}" for x in s["source_wrong"]]
                + [f"többlet (kézzel nézd át): {x}" for x in s["extra"]]) or "hibátlan"))
            detail += [f"    - figyelmeztetés: {w}" for w in r["warnings"]]
    out.append("\nCsoportonként:\n" + "\n".join(cats_out))
    out.append("\nFelidézés: az aranystandard elemei közül a megfelelő (vagy elfogadható másik) csoportban, szótővel "
               "egyezően megjelentek. Precizitás: a kinyert elemek közül egy aranystandard-elemnek megfelelők (a "
               "többletet a lista végén kézzel érdemes átnézni: lehet ugyanannak az elemnek a bontása is). Rossz "
               "forrás: az elem hivatkozott megszólalásai között nincs ott, ahol elhangzott.")
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
        (RESULTS / f"findings_raw_{stamp}.json").write_text(json.dumps(records, ensure_ascii=False, indent=2),
                                                            encoding="utf-8")
    text = report(records)
    (RESULTS / f"findings_report_{stamp}.md").write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
