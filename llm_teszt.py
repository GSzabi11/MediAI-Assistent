"""
Átfogó összehasonlító teszt a tünetkinyerő nyelvi modellekhez – mikrofon nélkül.

5 kitalált, eltérő jellegű SBO-s párbeszéden futtatja végig a kinyerést
(a teljes rendszert: prompt + kódoldali ellenőrzések), és külön méri:
  - pontszám: megtalált tünetek, tagadások, részletek, gyógyszerek, allergiák, előzmények
  - SÚLYOS hiba: tagadott tünet jelenlévőként, vagy kitalált gyógyszer/allergia
  - téves tagadás: meg nem válaszolt / bizonytalan tünet tagadottként rögzítve
  - kitalált vagy más tünettől átvett részlet (a kód törli, de a modell hibája)
  - rossz forrásmegjelölés, eltorzított név
  - sikertelen futás (időtúllépés / érvénytelen JSON), futásidő

A párbeszédek kitaláltak. Éles döntés előtt érdemes SBO-s orvossal átnézetni
és valós, anonimizált esetekkel bővíteni őket.

Használat (a beszed_pipeline.py-val egy mappában):
  python llm_teszt.py medgemma:latest gemma4:e4b             # 1 kör
  python llm_teszt.py medgemma:latest gemma4:e4b --runs 3    # a végső döntéshez
  python llm_teszt.py gemma4:e4b --cases hasi,idos --verbose # kiválasztott esetek, teljes JSON
"""
import argparse
import json
import re
import statistics
import sys
import time
from datetime import datetime

import requests

from beszed_pipeline import OLLAMA_URL, extract

# ---------------------------------------------------------------------------
# TESZTESETEK
# Szótövek: részszöveg-egyezés kisbetűsen. A "!" kezdetű tő kizáró feltétel
# (pl. a 'hány' ne egyezzen a 'hányinger'-rel).
# ---------------------------------------------------------------------------
HANYAS = ["hány", "vomit", "emesis", "!hányinger"]

CASES = [
    {
        "id": "mellkas",
        "cim": "Mellkasi fájdalom, dohányzó férfi, meg nem válaszolt kérdéssel",
        "dialog": [
            ("orvos", "Jó napot kívánok, mi hozta be ma a sürgősségire?"),
            ("beteg", "Tegnap este óta szorít a mellkasom, főleg bal oldalon, és kisugárzik a bal karomba."),
            ("orvos", "Mennyire erős a fájdalom egytől tízig?"),
            ("beteg", "Talán hetes. Lépcsőzéskor rosszabb."),
            ("orvos", "Nehezen kap levegőt? Volt láza?"),
            ("beteg", "Levegőt kicsit nehezebben kapok, de lázam nem volt."),
            ("orvos", "Hányt, vagy volt hányingere?"),
            ("beteg", "Hányni nem hánytam, de reggel nagyon izzadtam és szédültem egy kicsit."),
            ("orvos", "Milyen gyógyszereket szed rendszeresen?"),
            ("beteg", "Vérnyomásra ramiprilt, koleszterinre atorvasztatint."),
            ("orvos", "Van gyógyszerallergiája?"),
            ("beteg", "A penicillintől kiütéses leszek."),
            ("orvos", "Volt korábban szívbetegsége, cukorbetegsége?"),
            ("beteg", "Cukorbeteg nem vagyok, két éve magas vérnyomást állapítottak meg."),
            ("orvos", "Dohányzik?"),
            ("beteg", "Igen, napi egy doboz, harminc éve."),
        ],
        "present": [
            ("mellkasi fájdalom", ["mellkas"]),
            ("kisugárzás a bal karba", ["karba", "karom", "bal kar", "karfájd", "felső végtag"]),
            ("nehézlégzés", ["nehézlégz", "nehéz légz", "levegő", "dyspn", "légszomj"]),
            ("izzadás", ["izzad", "verejt"]),
            ("szédülés", ["szédül", "vertigo"]),
        ],
        "absent": [("láz", ["láz", "febr"]), ("hányás", HANYAS)],
        "not_denied": [("hányinger (nem válaszolt rá)", ["hányinger", "nausea"])],
        "details": [
            ("mellkasi fájdalom kezdete: tegnap este", ["mellkas"], ["tegnap"]),
            ("mellkasi fájdalom terhelésre romlik", ["mellkas", "fájdalom"], ["lépcső", "terhel"]),
            ("izzadás ideje: reggel", ["izzad", "verejt"], ["reggel"]),
        ],
        "meds": [("ramipril", ["ramipril"]), ("atorvasztatin", ["atorvasztatin", "atorvastatin"])],
        "allergies": [("penicillin", ["penicillin"])],
        "history": [("magas vérnyomás", ["vérnyomás", "hipert", "hypert"]), ("dohányzás", ["dohány", "cigaret"])],
    },
    {
        "id": "hasi",
        "cim": "Vándorló hasi fájdalom fiatal nőnél, eltérő időpontokkal",
        "dialog": [
            ("orvos", "Jó napot, mi a panasza?"),
            ("beteg", "Tegnap délután kezdett fájni a hasam a köldök körül, azóta átvándorolt jobb oldalra, alulra."),
            ("orvos", "Hányt?"),
            ("beteg", "Egyszer hánytam ma reggel, és egyáltalán nincs étvágyam."),
            ("orvos", "Láza volt?"),
            ("beteg", "Otthon harmincnyolc egész kettőt mértem."),
            ("orvos", "Hasmenés, székrekedés?"),
            ("beteg", "Nem, a széklet rendben van."),
            ("orvos", "Vizelési panasza van, csíp a vizelés?"),
            ("beteg", "Nincs."),
            ("orvos", "Mikor volt az utolsó menstruációja?"),
            ("beteg", "Két hete."),
            ("orvos", "Szed valamilyen gyógyszert? Allergiás valamire?"),
            ("beteg", "Csak fogamzásgátlót szedek. Az ibuprofentől csalánkiütést kapok."),
        ],
        "present": [
            ("hasi fájdalom", ["hasi", "hasfáj", "has fáj", "hasam", "alhas"]),
            ("hányás", HANYAS),
            ("étvágytalanság", ["étvágy", "anorex"]),
            ("láz", ["láz", "febr", "38", "hőemelked"]),
        ],
        "absent": [
            ("székletpanasz (hasmenés/székrekedés)", ["hasmen", "diarr", "székrek", "obstip", "széklet"]),
            ("vizelési panasz", ["vizel", "dysur", "dizur"]),
        ],
        "not_denied": [],
        "details": [
            ("hasi fájdalom helye: jobb alhas", ["hasi", "hasfáj", "has fáj", "hasam", "alhas"], ["jobb"]),
            ("hasi fájdalom kezdete: tegnap délután", ["hasi", "hasfáj", "has fáj", "hasam", "alhas"], ["tegnap", "délután"]),
            ("hányás ideje: ma reggel", HANYAS, ["reggel"]),
        ],
        "meds": [("fogamzásgátló", ["fogamzás", "kontracep", "antikoncip"])],
        "allergies": [("ibuprofen", ["ibuprofen"])],
        "history": [],
    },
    {
        "id": "idos",
        "cim": "Idős beteg bizonytalan válaszokkal, hozzátartozó közbeszól",
        "dialog": [
            ("orvos", "Jó napot, bácsi, mi történt?"),
            ("beteg", "Hát nem tudom... reggel valahogy furcsa volt minden."),
            ("hozzátartozó", "Reggel óta nem tudja rendesen mozgatni a jobb kezét, és akadozik a beszéde."),
            ("orvos", "Fáj a feje?"),
            ("beteg", "Talán egy kicsit, de nem biztos."),
            ("orvos", "Elesett?"),
            ("hozzátartozó", "Nem esett el, végig a fotelban ült."),
            ("orvos", "Milyen gyógyszereket szed?"),
            ("hozzátartozó", "Warfarint a pitvarfibrilláció miatt, és metformint a cukorbetegségére."),
            ("orvos", "Allergiás valamire?"),
            ("beteg", "Nem tudok róla."),
        ],
        "present": [
            ("jobb kéz gyengesége", ["kéz", "kez", "kar", "végtag", "mozgat", "gyeng", "parézis", "plégia"]),
            ("beszédzavar", ["beszéd", "dysarthr", "dizartr", "aphas", "afáz"]),
        ],
        "absent": [("elesés", ["esés", "elesett", "trauma"])],
        "not_denied": [("fejfájás (bizonytalan válasz)", ["fejfáj", "fej fáj", "cephal"])],
        "details": [
            ("kézgyengeség oldala: jobb", ["kéz", "kez", "kar", "végtag", "mozgat", "gyeng"], ["jobb"]),
            ("kézgyengeség kezdete: reggel", ["kéz", "kez", "kar", "végtag", "mozgat", "gyeng"], ["reggel"]),
        ],
        "meds": [("warfarin", ["warfarin"]), ("metformin", ["metformin"])],
        "allergies": [],
        "no_allergies": True,
        "history": [("pitvarfibrilláció", ["pitvar", "fibrill"]), ("cukorbetegség", ["cukor", "diabet"])],
    },
    {
        "id": "asztma",
        "cim": "Asztmás roham, több gyógyszer és allergia, összevont tagadás",
        "dialog": [
            ("orvos", "Mi a gond?"),
            ("beteg", "Két órája nagyon nehezen kapok levegőt, és sípol a mellkasom."),
            ("orvos", "Volt már ilyen?"),
            ("beteg", "Asztmás vagyok gyerekkorom óta, de ilyen rossz még sosem volt."),
            ("orvos", "Köhög? Van köpete?"),
            ("beteg", "Köhögök, de köpet nincs."),
            ("orvos", "Láza, mellkasi fájdalma van?"),
            ("beteg", "Egyik sem."),
            ("orvos", "Milyen gyógyszert használ?"),
            ("beteg", "Salbutamol sprayt, ma már négyszer fújtam, budezonidot reggel és este, és montelukasztot is szedek."),
            ("orvos", "Allergia?"),
            ("beteg", "Az aszpirinre és a macskaszőrre is allergiás vagyok."),
        ],
        "present": [
            ("nehézlégzés", ["nehézlégz", "nehéz légz", "levegő", "dyspn", "légszomj"]),
            ("sípoló légzés", ["sípol", "wheez", "zihál"]),
            ("köhögés", ["köhög", "tussis"]),
        ],
        "absent": [
            ("köpet", ["köpet", "expektor"]),
            ("láz", ["láz", "febr"]),
            ("mellkasi fájdalom", ["mellkasi fájd", "mellkasfájd", "mellkas fájd"]),
        ],
        "not_denied": [],
        "details": [
            ("nehézlégzés kezdete: két órája", ["nehézlégz", "nehéz légz", "levegő", "dyspn", "légszomj"], ["órá"]),
        ],
        "meds": [
            ("salbutamol", ["salbutamol"]),
            ("budezonid", ["budezonid", "budesonid"]),
            ("montelukaszt", ["montelukas"]),
        ],
        "allergies": [("aszpirin", ["aszpirin", "aspirin", "acetilszalicil"]), ("macskaszőr", ["macsk"])],
        "history": [("asztma", ["asztm", "asthm"])],
    },
    {
        "id": "fejfajas",
        "cim": "Hirtelen kezdetű fejfájás vészjelekkel, gyógyszer nélkül",
        "dialog": [
            ("orvos", "Jó napot, miért jött be?"),
            ("beteg", "Egy órája hirtelen iszonyú fejfájás kezdődött, mintha fejbe vágtak volna. Életem legrosszabb fejfájása."),
            ("orvos", "Szokott fájni a feje?"),
            ("beteg", "Migrénes vagyok, de az mindig lassan kezdődik, és sosem ilyen erős."),
            ("orvos", "Hányt? Zavarja a fény?"),
            ("beteg", "Kétszer hánytam, és a fény nagyon zavar."),
            ("orvos", "Merev a nyaka? Zsibbad valamije?"),
            ("beteg", "A nyakam kicsit merev, zsibbadást nem érzek."),
            ("orvos", "Szed vérhígítót vagy más gyógyszert?"),
            ("beteg", "Nem szedek semmilyen gyógyszert."),
        ],
        "present": [
            ("fejfájás", ["fejfáj", "fej fáj", "cephal"]),
            ("hányás", HANYAS),
            ("fényérzékenység", ["fény", "photo", "foto"]),
            ("nyakmerevség", ["nyak", "tarkó", "meningeal"]),
        ],
        "absent": [("zsibbadás", ["zsibb", "paresth", "pareszt"])],
        "not_denied": [],
        "details": [
            ("fejfájás hirtelen kezdet / egy órája", ["fejfáj", "fej fáj", "cephal"], ["hirtelen", "órá", "legrosszabb"]),
            ("hányás száma: kétszer", HANYAS, ["kétszer", "2"]),
        ],
        "meds": [],
        "no_meds": True,
        "allergies": [],
        "history": [("migrén", ["migrén", "migren"])],
    },
]


def case_chat(case: dict) -> list[dict]:
    return [{"id": i, "role": r, "text": t} for i, (r, t) in enumerate(case["dialog"])]


CHAT = case_chat(CASES[0])  # a medgemma_diag.py ezt használja


def case_max(case: dict) -> int:
    return (len(case["present"]) + len(case["absent"]) + len(case["details"])
            + len(case.get("meds", [])) + len(case.get("allergies", [])) + len(case.get("history", []))
            + int(bool(case.get("no_meds"))) + int(bool(case.get("no_allergies"))))


CASE_MAX = {c["id"]: case_max(c) for c in CASES}

# ---------------------------------------------------------------------------
# PONTOZÁS
# ---------------------------------------------------------------------------
NONE_WORDS = re.compile(r"^\s*(nincs|nem ismert|nem tud|semmi|nem szed|-|n/a|none)", re.I)


def matches(text: str, stems: list[str]) -> bool:
    text = text.lower()
    inc = [s for s in stems if not s.startswith("!")]
    exc = [s[1:] for s in stems if s.startswith("!")]
    return any(s in text for s in inc) and not any(s in text for s in exc)


def sym_text(s: dict, fields=("tunet", "lokalizacio", "jelleg_sulyossag")) -> str:
    return " ".join(str(s.get(k) or "") for k in fields)


def real_items(lst) -> list[str]:
    return [x for x in (lst or []) if isinstance(x, str) and x.strip() and not NONE_WORDS.match(x)]


def score_case(result: dict, case: dict) -> dict:
    symptoms = [s for s in result.get("tunetek", []) if isinstance(s, dict)]
    positive = [s for s in symptoms if s.get("jelen_van") is True]
    negative = [s for s in symptoms if s.get("jelen_van") is False]
    sc = {"points": 0, "max": 0, "severe": [], "false_negation": [], "missing": []}

    def point(ok: bool, miss_label: str):
        sc["max"] += 1
        if ok:
            sc["points"] += 1
        else:
            sc["missing"].append(miss_label)

    for name, stems in case["present"]:
        point(any(matches(sym_text(s), stems) for s in positive), name)

    for name, stems in case["absent"]:
        # tagadásnál csak a tünet nevét nézzük (a 'köhögés, köpet nélkül' ne legyen hamis riasztás)
        if any(matches(s.get("tunet", ""), stems) for s in positive):
            sc["max"] += 1
            sc["severe"].append(f"tagadott tünet jelenlévőként: {name}")
        else:
            point(any(matches(s.get("tunet", ""), stems) for s in negative), f"tagadás nincs rögzítve: {name}")

    for name, stems in case.get("not_denied", []):
        if any(matches(s.get("tunet", ""), stems) for s in negative):
            sc["false_negation"].append(name)

    for name, sym_stems, detail_stems in case["details"]:
        hits = [s for s in positive if matches(sym_text(s), sym_stems)]
        full = lambda s: sym_text(s, ("tunet", "lokalizacio", "kezdet_idotartam", "jelleg_sulyossag"))
        point(any(matches(full(s), detail_stems) for s in hits), f"részlet: {name}")

    for field, key, label in (("gyogyszerek", "meds", "gyógyszer"),
                              ("allergiak", "allergies", "allergia"),
                              ("elozmenyek", "history", "előzmény")):
        text = " ".join(real_items(result.get(field))).lower()
        for name, stems in case.get(key, []):
            point(matches(text, stems), f"{label}: {name}")

    for flag, field, label in (("no_meds", "gyogyszerek", "gyógyszer"), ("no_allergies", "allergiak", "allergia")):
        if case.get(flag):
            extra = real_items(result.get(field))
            sc["max"] += 1
            if extra:
                sc["severe"].append(f"kitalált {label}: {extra}")
            else:
                sc["points"] += 1

    warns = result.get("_figyelmeztetesek", [])
    count = lambda *prefixes: sum(1 for w in warns if w.startswith(prefixes))
    sc["fabricated"] = count("KITALÁLT", "ÁTVETT")
    sc["wrong_source"] = count("ROSSZ FORRÁS") + sum(1 for w in warns if "csak orvosi megszólalás" in w)
    sc["name_distortion"] = count("NÉV NEM EGYEZIK")
    sc["warnings"] = warns
    return sc


# ---------------------------------------------------------------------------
# FUTTATÁS
# ---------------------------------------------------------------------------
def unload(model: str):
    try:
        requests.post(OLLAMA_URL.replace("/api/chat", "/api/generate"),
                      json={"model": model, "keep_alive": 0}, timeout=30)
    except requests.RequestException:
        pass


def run_model(model: str, cases: list[dict], runs: int, verbose: bool) -> list[dict]:
    records = []
    for run in range(1, runs + 1):
        print(f"\n{'=' * 72}\n{model}   –   {run}. kör / {runs}\n{'=' * 72}")
        for case in cases:
            chat = case_chat(case)
            t0 = time.perf_counter()
            rec = {"model": model, "run": run, "case": case["id"]}
            try:
                result = extract(chat, "sbo", model=model, keep_loaded=True)
                rec["seconds"] = time.perf_counter() - t0
                rec["result"] = result
                rec.update(score_case(result, case))
                rec["ok"] = True
            except KeyboardInterrupt:
                raise
            except Exception as e:  # időtúllépés, érvénytelen JSON, Ollama-hiba
                rec.update(ok=False, seconds=time.perf_counter() - t0, error=str(e))
            records.append(rec)
            print_case(rec, case, verbose)
    unload(model)
    return records


def print_case(rec: dict, case: dict, verbose: bool):
    head = f"[{case['id']:<8}] {case['cim']}"
    if not rec["ok"]:
        print(f"{head}\n    ✗ SIKERTELEN ({rec['seconds']:.0f} mp): {rec['error']}")
        return
    print(f"{head}\n    {rec['points']}/{rec['max']} pont | {rec['seconds']:.1f} mp | "
          f"súlyos: {len(rec['severe'])} | téves tagadás: {len(rec['false_negation'])} | "
          f"kitalált/átvett: {rec['fabricated']} | rossz forrás: {rec['wrong_source']} | "
          f"névtorzítás: {rec['name_distortion']}")
    for s in rec["severe"]:
        print(f"    ✗✗ SÚLYOS: {s}")
    for s in rec["false_negation"]:
        print(f"    ✗  téves tagadás: {s}")
    if rec["missing"]:
        print(f"    –  hiányzik: {'; '.join(rec['missing'])}")
    if verbose:
        print(json.dumps(rec["result"], ensure_ascii=False, indent=2))


def summarize(records: list[dict], models: list[str], n_cases: int, runs: int):
    print(f"\n\n{'#' * 96}\nÖSSZESÍTÉS  ({n_cases} eset × {runs} kör modellenként)\n{'#' * 96}")
    header = (f"{'Modell':<18}{'Pont %':>16}{'Súlyos':>8}{'Téves tag.':>11}"
              f"{'Kitalált':>10}{'Rossz forr.':>12}{'Névtorz.':>9}{'Sikertelen':>11}{'Átl. idő':>10}")
    print(header + "\n" + "-" * len(header))
    for m in models:
        recs = [r for r in records if r["model"] == m]
        ok = [r for r in recs if r["ok"]]
        if not recs:
            continue
        per_run = []
        for run in sorted({r["run"] for r in recs}):
            rr = [r for r in recs if r["run"] == run]
            pts = sum(r.get("points", 0) for r in rr)
            # sikertelen futás 0 pont, de az eset teljes maximuma beleszámít
            mx = sum(CASE_MAX[r["case"]] for r in rr)
            per_run.append(100 * pts / mx if mx else 0)
        pct = (f"{statistics.mean(per_run):.0f}%" +
               (f" ({min(per_run):.0f}–{max(per_run):.0f})" if len(per_run) > 1 else ""))
        tot = lambda k: sum(len(r[k]) if isinstance(r.get(k), list) else r.get(k, 0) for r in ok)
        avg_t = statistics.mean(r["seconds"] for r in ok) if ok else 0
        print(f"{m:<18}{pct:>16}{tot('severe'):>8}{tot('false_negation'):>11}"
              f"{tot('fabricated'):>10}{tot('wrong_source'):>12}{tot('name_distortion'):>9}"
              f"{len(recs) - len(ok):>11}{avg_t:>9.1f}s")
    print("\nOlvasási sorrend a döntéshez: 1) súlyos hiba és sikertelen futás  2) téves tagadás, "
          "névtorzítás  3) pontszám (kihagyások)  4) kitalált részlet (a kód törli)  5) idő."
          "\nAz átlagidő az első eset modellbetöltését is tartalmazza.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("models", nargs="*", default=["medgemma:latest"])
    ap.add_argument("--runs", type=int, default=1, help="ismétlések száma (a szórás méréséhez)")
    ap.add_argument("--cases", help="vesszővel elválasztott eset-azonosítók: " + ",".join(c["id"] for c in CASES))
    ap.add_argument("--verbose", action="store_true", help="a teljes JSON kiírása esetenként")
    args = ap.parse_args()

    cases = CASES
    if args.cases:
        wanted = {c.strip() for c in args.cases.split(",")}
        cases = [c for c in CASES if c["id"] in wanted]
        if not cases:
            sys.exit(f"Nincs ilyen eset. Választható: {', '.join(c['id'] for c in CASES)}")

    try:
        tags = requests.get(OLLAMA_URL.replace("/api/chat", "/api/tags"), timeout=10).json()["models"]
        installed = {t["name"] for t in tags}
    except requests.RequestException:
        sys.exit("Nem érem el az Ollamát – fut a háttérben?")

    models = []
    for m in args.models:
        if m in installed or f"{m}:latest" in installed:
            models.append(m)
        else:
            print(f"⚠ {m} nincs letöltve (ollama pull {m}) – kihagyom.")

    records = []
    try:
        for m in models:
            records += run_model(m, cases, args.runs, args.verbose)
    except KeyboardInterrupt:
        print("\n\nMegszakítva – az eddigi eredmények összesítése:")

    if records:
        summarize(records, models, len(cases), args.runs)
        out = f"benchmark_{datetime.now():%Y%m%d_%H%M%S}.json"
        with open(out, "w", encoding="utf-8") as f:
            json.dump(records, f, ensure_ascii=False, indent=2)
        print(f"\nRészletes eredmények (minden JSON-nal): {out}")


if __name__ == "__main__":
    main()