"""PDF-laborkinyerés mérése (IDK 2. táblázat 2. sor, C4 M4) — szintetikus leleteken, kézi aranystandarddal.

A szkript maga készíti a leleteket (PyMuPDF, két elrendezés: EESZT-szerű és magánlabor-szerű táblázat),
így az aranystandard a lelet forrása, nem utólagos címkézés. A termék feldolgozó útját hívja, a Spring és
az adatbázis nélkül: pdf_text → classify → llm.extract → extract.lab_results → locate.add_source_boxes.

Mérőszámok: soronkénti felidézés (az aranystandard sorai közül hány került be helyes értékkel), precizitás
(a kinyert sorok közül hány egyezik egy aranystandard-sorral), mezőnkénti pontosság az egyező sorokon
(mértékegység, referencia alsó / felső határ, szöveges referencia, cenzorálás jele), PDF-beli sorkiemelés
megtalálása, idő / lelet. A betegek és az értékek kitaláltak.

Használat (a docproc környezetével; a modell a DOC_LLM_MODEL környezeti változóból, mint a terméknél):
  set DOC_LLM_MODEL=medgemma:latest & ..\\docproc\\.venv\\Scripts\\python lab_eval.py
  ..\\docproc\\.venv\\Scripts\\python lab_eval.py --model medgemma:27b
"""
import argparse
import json
import os
import re
import sys
import time
import unicodedata
from datetime import datetime
from decimal import Decimal
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
LABS = HERE / "data" / "labs"

# (szakasz, név, eredmény a leleten, mértékegység, referencia a leleten)
PANELS = {
    "lab1_eeszt": {"layout": "eeszt", "date": "2026.09.14.", "patient": "Teszt Béla", "rows": [
        ("Hematológia", "Fehérvérsejtszám (WBC)", "11,8", "G/L", "4,0 - 10,0"),
        ("Hematológia", "Vörösvérsejtszám (RBC)", "4,21", "T/L", "4,50 - 5,90"),
        ("Hematológia", "Hemoglobin", "128", "g/L", "135 - 175"),
        ("Hematológia", "Hematokrit", "0,382", "L/L", "0,400 - 0,520"),
        ("Hematológia", "MCV", "90,7", "fL", "80,0 - 100,0"),
        ("Hematológia", "MCH", "30,4", "pg", "27,0 - 34,0"),
        ("Hematológia", "MCHC", "335", "g/L", "310 - 370"),
        ("Hematológia", "RDW-CV", "14,9", "%", "11,5 - 14,5"),
        ("Hematológia", "Thrombocytaszám", "312", "G/L", "150 - 400"),
        ("Hematológia", "Neutrophil granulocyta %", "78,2", "%", "40,0 - 75,0"),
        ("Hematológia", "Lymphocyta %", "14,1", "%", "20,0 - 45,0"),
        ("Hematológia", "Monocyta %", "6,0", "%", "2,0 - 10,0"),
        ("Hematológia", "Eosinophil granulocyta %", "1,2", "%", "0,0 - 6,0"),
        ("Hematológia", "Basophil granulocyta %", "0,5", "%", "0,0 - 2,0"),
        ("Klinikai kémia", "Glükóz", "7,9", "mmol/L", "3,9 - 6,1"),
        ("Klinikai kémia", "Karbamid", "9,4", "mmol/L", "2,8 - 8,1"),
        ("Klinikai kémia", "Kreatinin", "132", "µmol/L", "62 - 106"),
        ("Klinikai kémia", "eGFR-EPI", ">90", "mL/min/1,73m2", "> 60"),
        ("Klinikai kémia", "Nátrium", "136", "mmol/L", "136 - 145"),
        ("Klinikai kémia", "Kálium", "5,6", "mmol/L", "3,5 - 5,1"),
        ("Klinikai kémia", "Klorid", "101", "mmol/L", "98 - 107"),
        ("Klinikai kémia", "Húgysav", "421", "µmol/L", "202 - 417"),
        ("Klinikai kémia", "Összfehérje", "68,4", "g/L", "66,0 - 83,0"),
        ("Klinikai kémia", "Albumin", "38,1", "g/L", "35,0 - 52,0"),
        ("Klinikai kémia", "Összbilirubin", "14,2", "µmol/L", "5,0 - 21,0"),
        ("Klinikai kémia", "GOT (AST)", "41", "U/L", "< 40"),
        ("Klinikai kémia", "GPT (ALT)", "38", "U/L", "< 41"),
        ("Klinikai kémia", "Gamma-GT", "72", "U/L", "< 60"),
        ("Klinikai kémia", "Alkalikus foszfatáz", "96", "U/L", "40 - 130"),
        ("Klinikai kémia", "LDH", "245", "U/L", "< 250"),
        ("Klinikai kémia", "CRP", "<0,5", "mg/L", "< 5,0"),
        ("Klinikai kémia", "Kalcium", "2,31", "mmol/L", "2,15 - 2,55"),
        ("Klinikai kémia", "Magnézium", "0,78", "mmol/L", "0,66 - 1,07"),
        ("Klinikai kémia", "Foszfát", "1,12", "mmol/L", "0,81 - 1,45"),
        ("Klinikai kémia", "Vas", "11,3", "µmol/L", "12,5 - 32,2"),
        ("Klinikai kémia", "Ferritin", "38", "µg/L", "30 - 400"),
        ("Klinikai kémia", "NT-proBNP", "1840", "pg/mL", "< 125"),
    ]},
    "lab2_privat": {"layout": "privat", "date": "2026.10.02.", "patient": "Próba Anna", "rows": [
        ("Vérkép", "Fehérvérsejt", "6,4", "Giga/L", "3,8 - 10,0"),
        ("Vérkép", "Vörösvérsejt", "4,62", "Tera/L", "3,90 - 5,20"),
        ("Vérkép", "Hemoglobin", "131", "g/L", "117 - 153"),
        ("Vérkép", "Hematokrit", "39,8", "%", "35,0 - 46,0"),
        ("Vérkép", "MCV", "86,1", "fL", "80,0 - 98,0"),
        ("Vérkép", "Thrombocyta", "198", "Giga/L", "150 - 400"),
        ("Anyagcsere", "Éhgyomri vércukor", "5,4", "mmol/L", "3,9 - 5,6"),
        ("Anyagcsere", "HbA1c (IFCC)", "41", "mmol/mol", "< 42"),
        ("Anyagcsere", "Koleszterin összes", "6,3", "mmol/L", "< 5,2"),
        ("Anyagcsere", "HDL-koleszterin", "1,21", "mmol/L", "> 1,20"),
        ("Anyagcsere", "LDL-koleszterin", "4,4", "mmol/L", "< 3,0"),
        ("Anyagcsere", "Triglicerid", "1,9", "mmol/L", "< 1,7"),
        ("Vese", "Kreatinin", "71", "µmol/L", "45 - 84"),
        ("Vese", "eGFR (CKD-EPI)", "88", "mL/min/1,73m2", "> 90"),
        ("Vese", "Karbamid", "5,1", "mmol/L", "2,8 - 7,2"),
        ("Májfunkció", "ASAT (GOT)", "22", "U/L", "< 32"),
        ("Májfunkció", "ALAT (GPT)", "19", "U/L", "< 33"),
        ("Májfunkció", "GGT", "18", "U/L", "< 40"),
        ("Májfunkció", "Bilirubin, összes", "9,8", "µmol/L", "< 21,0"),
        ("Pajzsmirigy", "TSH", "4,92", "mU/L", "0,27 - 4,20"),
        ("Pajzsmirigy", "Szabad T4 (FT4)", "13,1", "pmol/L", "12,0 - 22,0"),
        ("Pajzsmirigy", "Anti-TPO", "112", "IU/mL", "< 34"),
        ("Gyulladás", "CRP", "2,1", "mg/L", "< 5,0"),
        ("Gyulladás", "Süllyedés (We)", "18", "mm/h", "< 20"),
        ("Vitaminok", "25-OH-D-vitamin", "42", "nmol/L", "75 - 250"),
        ("Vitaminok", "B12-vitamin", "298", "pmol/L", "145 - 569"),
        ("Vitaminok", "Folsav", ">45,4", "nmol/L", "> 7,0"),
        ("Vizelet", "Fajsúly", "1018", "g/L", "1005 - 1030"),
        ("Vizelet", "pH", "6,0", "", "5,0 - 7,5"),
        ("Vizelet", "Fehérje", "Negatív", "", "Negatív"),
        ("Vizelet", "Glükóz", "Negatív", "", "Negatív"),
        ("Vizelet", "Keton", "Negatív", "", "Negatív"),
        ("Vizelet", "Urobilinogén", "Normális", "", "Normális"),
        ("Vizelet", "Bilirubin", "Negatív", "", "Negatív"),
        ("Vizelet", "Nitrit", "Pozitív", "", "Negatív"),
        ("Vizelet", "Leukocyta", "kevés", "", "Negatív"),
        ("Vizelet", "Vér", "Negatív", "", "Negatív"),
    ]},
    "lab3_eeszt": {"layout": "eeszt", "date": "2026.10.11.", "patient": "Minta Géza", "rows": [
        ("Véralvadás", "Protrombin idő (INR)", "2,8", "", "0,8 - 1,2"),
        ("Véralvadás", "Protrombin %", "31", "%", "70 - 120"),
        ("Véralvadás", "APTI", "34,2", "s", "25,0 - 37,0"),
        ("Véralvadás", "Fibrinogén", "4,1", "g/L", "2,0 - 4,0"),
        ("Véralvadás", "D-dimer", "0,42", "mg/L FEU", "< 0,50"),
        ("Szív", "Troponin T (hs)", "38", "ng/L", "< 14"),
        ("Szív", "CK", "184", "U/L", "< 190"),
        ("Szív", "CK-MB", "21", "U/L", "< 24"),
        ("Elektrolit", "Nátrium", "131", "mmol/L", "136 - 145"),
        ("Elektrolit", "Kálium", "3,3", "mmol/L", "3,5 - 5,1"),
        ("Elektrolit", "Magnézium", "0,61", "mmol/L", "0,66 - 1,07"),
        ("Vese", "Kreatinin", "156", "µmol/L", "62 - 106"),
        ("Vese", "eGFR-EPI", "38", "mL/min/1,73m2", "> 60"),
        ("Vese", "Cisztatin C", "1,62", "mg/L", "0,61 - 0,95"),
        ("Gyulladás", "Prokalcitonin", "<0,05", "ng/mL", "< 0,50"),
        ("Gyulladás", "CRP", "48,7", "mg/L", "< 5,0"),
        ("Gyulladás", "Laktát", "2,4", "mmol/L", "0,5 - 2,2"),
        ("Pajzsmirigy", "TSH", "1,85", "mU/L", "0,27 - 4,20"),
    ]},
}


# ---------------------------------------------------------------------------
# A LELETEK ELŐÁLLÍTÁSA
# ---------------------------------------------------------------------------
def make_pdf(pid: str, panel: dict, path: Path):
    import pymupdf
    font = "C:/Windows/Fonts/arial.ttf"
    doc = pymupdf.open()
    page = doc.new_page(width=595, height=842)
    y = 50

    def text(x, s, size=9, bold=False):
        page.insert_text((x, y), s, fontsize=size, fontname="arialbd" if bold else "arial",
                         fontfile=font.replace("arial.ttf", "arialbd.ttf") if bold else font)

    if panel["layout"] == "eeszt":
        text(40, "Szintetikus Megyei Kórház – Központi Laboratórium", 11, True); y += 18
        text(40, "LABORATÓRIUMI LELET", 13, True); y += 20
        text(40, f"Név: {panel['patient']}     TAJ: 000 000 000     Szül. dátum: 1950.01.01."); y += 14
        text(40, f"Mintavétel dátuma: {panel['date']}     Beküldő: Belgyógyászati osztály"); y += 22
        cols = (40, 250, 330, 420)
        heads = ("Vizsgálat", "Eredmény", "Mértékegység", "Referencia tartomány")
    else:
        text(40, "Szintetikus Diagnosztika Kft. – magánlaboratórium", 11, True); y += 18
        text(40, "Laboratóriumi lelet", 13, True); y += 20
        text(40, f"Páciens: {panel['patient']}   Azonosító: 000000000   Leletezés: {panel['date']}"); y += 22
        cols = (40, 230, 300, 380)
        heads = ("Vizsgálat neve", "Eredmény", "Egység", "Ref. tartomány")
    for x, h in zip(cols, heads):
        text(x, h, 9, True)
    y += 14
    section = None
    for sec, name, value, unit, ref in panel["rows"]:
        if y > 800:
            page = doc.new_page(width=595, height=842)
            y = 50
        if sec != section:
            y += 4
            text(40, sec, 10, True)
            y += 14
            section = sec
        flag = ""
        if panel["layout"] == "privat":
            flag = _flag(value, ref)
        text(cols[0], name)
        text(cols[1], value + (f" {flag}" if flag else ""))
        text(cols[2], unit)
        text(cols[3], ref)
        y += 13
    y += 20
    text(40, "A lelet szintetikus, mérési célra készült; valós beteghez nem tartozik.", 8)
    doc.save(path)


def _flag(value: str, ref: str) -> str:
    v, lo, hi = _num(value), *_ref(ref)
    if v is None or value[0] in "<>":
        return ""
    if lo is not None and v < lo:
        return "L"
    if hi is not None and v > hi:
        return "H"
    return ""


# ---------------------------------------------------------------------------
# ARANYSTANDARD ÉS ÖSSZEVETÉS
# ---------------------------------------------------------------------------
def _num(s: str):
    s = (s or "").strip().lstrip("<>=").strip().replace(",", ".")
    try:
        return Decimal(s)
    except Exception:
        return None


def _ref(ref: str):
    ref = ref.strip()
    if m := re.fullmatch(r"([\d,]+)\s*-\s*([\d,]+)", ref):
        return _num(m.group(1)), _num(m.group(2))
    if m := re.fullmatch(r"<\s*([\d,]+)", ref):
        return None, _num(m.group(1))
    if m := re.fullmatch(r">\s*([\d,]+)", ref):
        return _num(m.group(1)), None
    return None, None


def gold_rows(panel: dict) -> list[dict]:
    out = []
    for sec, name, value, unit, ref in panel["rows"]:
        lo, hi = _ref(ref)
        numeric = _num(value) is not None
        out.append({"name": name, "value": _num(value) if numeric else None,
                    "comparator": value[0] if value[0] in "<>" else None,
                    "valueText": None if numeric else value, "unit": unit or None,
                    "refLow": lo, "refHigh": hi, "refText": None if numeric or lo or hi else ref})
    return out


def key(s: str) -> str:
    s = "".join(c for c in unicodedata.normalize("NFKD", (s or "").casefold()) if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]", "", s)


def same_name(gold: str, got: str) -> bool:
    g, x = key(gold), key(got)
    return bool(g and x) and (g == x or (len(x) >= 3 and x in g) or (len(g) >= 3 and g in x))


def unit_key(u):
    return key(u or "").replace("giga", "g").replace("tera", "t") or None


def dec(x):
    return None if x in (None, "") else Decimal(str(x))


def compare(gold: list[dict], got: list[dict]) -> dict:
    used, matches = set(), []
    for g in gold:
        cands = [i for i, r in enumerate(got) if i not in used and same_name(g["name"], r["name"])
                 and (g["valueText"] is None) == (r.get("valueText") is None)]
        # a több egyező név közül (pl. két „Kreatinin”) az értékben is egyező
        exact = [i for i in cands if _value_ok(g, got[i])]
        pick = (exact or cands or [None])[0]
        if pick is not None:
            used.add(pick)
        matches.append((g, None if pick is None else got[pick]))
    found = [(g, r) for g, r in matches if r is not None]
    correct = [(g, r) for g, r in found if _value_ok(g, r)]
    fields = {"mértékegység": [unit_key(g["unit"]) == unit_key(r.get("unit")) for g, r in correct],
              "ref. alsó": [g["refLow"] == dec(r.get("refLow")) for g, r in correct],
              "ref. felső": [g["refHigh"] == dec(r.get("refHigh")) for g, r in correct],
              "szöveges ref.": [key(g["refText"] or "") == key(r.get("refText") or "") for g, r in correct
                                if g["valueText"] is not None],
              "cenzorálás jele": [g["comparator"] == r.get("comparator") for g, r in correct]}
    return {
        "gold": len(gold), "extracted": len(got), "recall": len(correct),
        "wrong_value": [f"{g['name']}: elvárt {g['value'] if g['valueText'] is None else g['valueText']}"
                        f"{g['comparator'] or ''}, kinyert {r.get('comparator') or ''}{r.get('value') or r.get('valueText')}"
                        for g, r in found if not _value_ok(g, r)],
        "missing": [g["name"] for g, r in matches if r is None],
        "extra": [r["name"] for i, r in enumerate(got) if i not in used],
        "fields": {k: [sum(v), len(v)] for k, v in fields.items()},
        "boxes": [sum(1 for _, r in correct if r.get("sourceBox")), len(correct)],
        "numeric_gold": sum(1 for g in gold if g["valueText"] is None),
        "numeric_recall": sum(1 for g, _ in correct if g["valueText"] is None),
    }


def _value_ok(g: dict, r: dict) -> bool:
    if g["valueText"] is not None:
        return key(g["valueText"]) == key(r.get("valueText") or "")
    return g["value"] == dec(r.get("value")) and g["comparator"] == r.get("comparator")


# ---------------------------------------------------------------------------
# FUTTATÁS
# ---------------------------------------------------------------------------
def run(model: str, panels: list[str]) -> list[dict]:
    os.environ["DOC_LLM_MODEL"] = model
    sys.path.insert(0, str(HERE.parent / "docproc"))
    import classify, extract, llm, locate, pdf_text  # noqa: E401 — a modellválasztás után
    records = []
    for pid in panels:
        panel = PANELS[pid]
        path = LABS / f"{pid}.pdf"
        text = pdf_text.extract_text(path)
        doc_type = classify.classify(text)
        warnings: list[str] = []
        t0 = time.perf_counter()
        rec = {"model": model, "panel": pid, "docType": doc_type}
        try:
            data, w = llm.extract(doc_type, text)
            warnings += w
            date = extract.resolve_clinical_date(data.get("datum"), text, warnings)
            rows = extract.lab_results(data, text, warnings)
            locate.add_source_boxes(path, rows)
            rec.update(clinicalDate=date.isoformat() if date else None,
                       rows=[{k: v for k, v in r.items() if not k.startswith("_")} for r in rows])
        except Exception as e:
            rec.update(error=f"{type(e).__name__}: {e}", rows=[])
        rec["seconds"] = round(time.perf_counter() - t0, 1)
        rec["warnings"] = warnings
        print(f"{model:<16} {pid:<12} {rec['seconds']:6.1f} s  {len(rec['rows'])} sor"
              f"{'  HIBA: ' + rec['error'] if 'error' in rec else ''}", flush=True)
        records.append(rec)
    return records


def report(records: list[dict]) -> str:
    out = ["# PDF-laborkinyerés — szintetikus leletek, kézi aranystandard\n",
           "| Modell | Lelet | Idő | Felidézés (helyes érték) | Precizitás | Rossz érték | Mértékegység | "
           "Ref. alsó | Ref. felső | Szöveges ref. | Cenzorálás | PDF-kiemelés | Dátum |",
           "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    detail = []
    for r in records:
        panel = PANELS[r["panel"]]
        c = compare(gold_rows(panel), r["rows"])
        r["score"] = c
        f = c["fields"]
        fx = lambda k: f"{f[k][0]}/{f[k][1]}"
        precise = c["extracted"] - len(c["extra"]) - len(c["wrong_value"])
        expected_date = datetime.strptime(panel["date"], "%Y.%m.%d.").date().isoformat()
        out.append(f"| {r['model']} | {r['panel']} ({c['gold']} sor) | {r['seconds']:.0f} s | {c['recall']}/{c['gold']} "
                   f"| {precise}/{c['extracted']} | {len(c['wrong_value'])} | {fx('mértékegység')} | {fx('ref. alsó')} "
                   f"| {fx('ref. felső')} | {fx('szöveges ref.')} | {fx('cenzorálás jele')} | {c['boxes'][0]}/{c['boxes'][1]} "
                   f"| {'helyes' if r.get('clinicalDate') == expected_date else r.get('clinicalDate')} |")
        detail.append(f"- {r['model']} {r['panel']}: " + ("; ".join(
            ([f"HIBA: {r['error']}"] if "error" in r else []) + [f"rossz érték: {x}" for x in c["wrong_value"]]
            + [f"hiányzik: {x}" for x in c["missing"]] + [f"többlet: {x}" for x in c["extra"]]) or "hibátlan"))
    for m in dict.fromkeys(r["model"] for r in records):
        rr = [r for r in records if r["model"] == m]
        g = sum(r["score"]["gold"] for r in rr)
        rec = sum(r["score"]["recall"] for r in rr)
        ex = sum(r["score"]["extracted"] for r in rr)
        ok = sum(r["score"]["extracted"] - len(r["score"]["extra"]) - len(r["score"]["wrong_value"]) for r in rr)
        ng, nr = sum(r["score"]["numeric_gold"] for r in rr), sum(r["score"]["numeric_recall"] for r in rr)
        out.append(f"\n**{m} összesen**: felidézés {rec}/{g} ({100 * rec / g:.1f}%; numerikus sorok {nr}/{ng}), "
                   f"precizitás {ok}/{ex} ({100 * ok / max(ex, 1):.1f}%), "
                   f"idő {', '.join(f'{r['seconds']:.0f}' for r in rr)} s/lelet")
    out.append("\nFelidézés: az aranystandard sorai közül a helyes értékkel (szám + cenzorálási jel, vagy szöveges "
               "eredmény) bekerültek. Precizitás: a kinyert sorok közül az aranystandard egy sorával névben és "
               "értékben egyezők. A mezőnkénti oszlopok a helyes értékű sorokon számolnak. A mértékegység írásmódja "
               "(Giga/L = G/L) nem számít eltérésnek.")
    return "\n".join(out + ["\n## Részletek\n"] + detail)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=os.environ.get("DOC_LLM_MODEL", "medgemma:latest"))
    ap.add_argument("--panels", help="vesszővel: " + ",".join(PANELS))
    ap.add_argument("--rescore", type=Path)
    args = ap.parse_args()
    RESULTS.mkdir(exist_ok=True)
    LABS.mkdir(parents=True, exist_ok=True)
    stamp = f"{datetime.now():%Y%m%d_%H%M%S}"
    if args.rescore:
        records = json.loads(args.rescore.read_text(encoding="utf-8"))
    else:
        panels = args.panels.split(",") if args.panels else list(PANELS)
        for pid in panels:
            make_pdf(pid, PANELS[pid], LABS / f"{pid}.pdf")
        records = run(args.model, panels)
        (RESULTS / f"lab_raw_{key(args.model)}_{stamp}.json").write_text(
            json.dumps(records, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    text = report(records)
    (RESULTS / f"lab_report_{key(records[0]['model'])}_{stamp}.md").write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
