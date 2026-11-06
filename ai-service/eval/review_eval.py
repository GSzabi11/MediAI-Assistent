"""A hangátnézés mérése (IDK 2. táblázat 4. sor) — kitalált átiraton, beültetett felismerési hibákkal.

A termék kódját hívja (voice/review.py: célzott kérdések + determinisztikus őrök). A bizonytalan szakaszokat
(amit élesben a Whisper alacsony szó-valószínűsége jelöl ki) itt az aranystandard adja meg: egy részük valódi
hiba (a helyes alak ismert), egy részük helyes szó, amit a modellnek NEM szabad átírnia. Beszélőcsere: két
megszólalás rossz beszélőhöz van rendelve (tartalom alapján gyanús). Echo-diktálás: minden érték kérdés,
ebből kettő nem lehetséges (pl. EF 555%) — ezeket kell gyanúsnak jelölni, a többit nem.

A hang nincs a mérésben (WER, diarizáció nem); csak az átnéző lépést méri.

Használat (az ai-service/voice ai_env környezetével):
  ..\\voice\\ai_env\\Scripts\\python review_eval.py medgemma:latest medgemma:27b
"""
import argparse
import copy
import json
import os
import sys
import time
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
sys.path.insert(0, str(HERE.parent / "voice"))

# (szerep a gépi átiratban, gépi szöveg, bizonytalan szakaszok {szakasz: helyes alak vagy None = helyes}, valódi szerep)
SBO = [
    ("orvos", "Jó napot, mi a panasza?", {}, "orvos"),
    ("beteg", "Tegnap óta szorít a mellkasom, és a bal karomba sugárzik.", {"szorít": None}, "beteg"),
    ("orvos", "Milyen gyógyszereket szed?", {}, "orvos"),
    ("beteg", "Vérnyomásra rami prilt szedek, koleszterinre atorva sztatint.",
     {"rami prilt": "ramiprilt", "atorva sztatint": "atorvasztatint"}, "beteg"),
    ("orvos", "Cukorbetegsége van?", {}, "orvos"),
    ("beteg", "Igen, esténként inulint adok be magamnak, és metformint is szedek.",
     {"inulint": "inzulint", "metformint": None}, "beteg"),
    ("orvos", "Van gyógyszerallergiája?", {}, "orvos"),
    ("beteg", "A penicilintől kiütéses leszek.", {"penicilintől": "penicillintől"}, "beteg"),
    ("orvos", "Volt már szívinfarktusa?", {"szívinfarktusa": None}, "orvos"),
    ("orvos", "Két éve volt egy kisebb, akkor sztentet kaptam.", {"sztentet": None}, "beteg"),
    ("beteg", "És most milyen vizsgálatokat kér, doktor úr?", {}, "orvos"),
    ("orvos", "EKG-t és troponint kérek, és adok egy aszpirint.", {"troponint": None}, "orvos"),
    ("beteg", "Szívritmusra meto prololt is szedek.", {"meto prololt": "metoprololt"}, "beteg"),
    ("orvos", "Rendben, a nitroglicerin spray-t használta?", {"nitroglicerin": None}, "orvos"),
    ("beteg", "Igen, kétszer fújtam, de nem múlt el.", {}, "beteg"),
    ("orvos", "A pulzusa százhúsz, a vérnyomása száz per hatvan.", {"százhúsz": None}, "orvos"),
]
# echo-diktálás: (szöveg, a gyanúsnak jelölendő értékek)
ECHO = [
    ("Bal kamra végdiasztolés átmérője 52 milliméter, a szeptum 11 milliméter.", set()),
    ("Az ejekciós frakció 555 százalék, a fali mozgászavar nem látható.", {"555 százalék"}),
    ("E/A arány 0,8, a septalis e' 7 centiméter per szekundum.", set()),
    ("TAPSE 21 milliméter, a jobb kamra nem tágult.", set()),
    ("A bal pitvar átmérője 41 milliméter.", set()),
    ("Az aorta gyök átmérője 340 milliméter.", {"340 milliméter"}),
    ("A becsült pulmonalis nyomás 32 Hgmm.", set()),
]


def chat_sbo():
    return [{"id": i, "role": r, "text": t, "uncertain": list(u)} for i, (r, t, u, _) in enumerate(SBO)]


def chat_echo():
    return [{"id": i, "role": "orvos", "text": t, "uncertain": []} for i, (t, _) in enumerate(ECHO)]


def score_sbo(out: list[dict]) -> dict:
    sc = {"fixed": [], "not_fixed": [], "wrong_fix": [], "kept_ok": 0, "broken": [], "speaker_ok": [],
          "speaker_missed": [], "speaker_wrong": []}
    for (role, text, spans, true_role), t in zip(SBO, out):
        for span, correct in spans.items():
            if correct is None:
                if span in t["text"]:
                    sc["kept_ok"] += 1
                else:
                    sc["broken"].append(f"[{t['id']}] «{span}» → {t['text']}")
            elif correct in t["text"]:
                sc["fixed"].append(f"«{span}» → «{correct}»")
            elif span in t["text"]:
                sc["not_fixed"].append(f"«{span}» (elvárt «{correct}»)")
            else:
                sc["wrong_fix"].append(f"«{span}» → {t['text']} (elvárt «{correct}»)")
        if role != true_role:
            (sc["speaker_ok"] if t["role"] == true_role else sc["speaker_missed"]).append(f"[{t['id']}] {text[:50]}")
        elif t["role"] != true_role:
            sc["speaker_wrong"].append(f"[{t['id']}] {role} → {t['role']}: {text[:50]}")
    return sc


def score_echo(out: list[dict]) -> dict:
    sc = {"flagged_ok": [], "missed": [], "false_flag": []}
    for (text, bad), t in zip(ECHO, out):
        note = t.get("review_note") or ""
        for v in bad:
            (sc["flagged_ok"] if v in note else sc["missed"]).append(v)
        for v in [x.split("»")[0] for x in note.split("«")[1:]]:
            if v not in bad:
                sc["false_flag"].append(v)
    return sc


def run(models: list[str]) -> list[dict]:
    import beszed_pipeline as bp
    import review
    base = os.environ.get("OLLAMA_URL", "http://localhost:11434").rstrip("/")
    bp.OLLAMA_URL = base if base.endswith("/api/chat") else base + "/api/chat"
    bp.LLM_DEADLINE = max(bp.LLM_DEADLINE, 900)
    records = []
    for model in models:
        review.REVIEW_MODEL = model
        for mode, make in (("sbo", chat_sbo), ("echo", chat_echo)):
            chat = make()
            t0 = time.perf_counter()
            out, used, warnings = review.review(copy.deepcopy(chat), mode)
            sec = round(time.perf_counter() - t0, 1)
            print(f"{model:<16} {mode:<5} {sec:6.1f} s  modell: {used}", flush=True)
            records.append({"model": model, "mode": mode, "seconds": sec, "ran": used is not None,
                            "out": out, "warnings": warnings})
    return records


def report(records: list[dict]) -> str:
    n_err = sum(1 for _, _, u, _ in SBO for c in u.values() if c)
    n_ok = sum(1 for _, _, u, _ in SBO for c in u.values() if c is None)
    n_spk = sum(1 for r, _, _, tr in SBO if r != tr)
    n_bad = sum(len(b) for _, b in ECHO)
    out = ["# Hangátnézés — kitalált átirat, beültetett hibák\n",
           f"SBO: {n_err} beültetett szóhiba, {n_ok} bizonytalannak jelölt, de helyes szó, {n_spk} rossz beszélő. "
           f"Echo: {sum(1 for _ in ECHO)} megszólalás, {n_bad} nem lehetséges érték.\n",
           "| Modell | Szóhiba javítva | Rosszul javítva | Helyes szó elrontva | Beszélő javítva | Beszélő elrontva | "
           "Őr elvetette | Echo: gyanús jelölve | Echo: téves jelölés | Idő (SBO / echo) |",
           "|---|---|---|---|---|---|---|---|---|---|"]
    detail = []
    for m in dict.fromkeys(r["model"] for r in records):
        s = next(r for r in records if r["model"] == m and r["mode"] == "sbo")
        e = next(r for r in records if r["model"] == m and r["mode"] == "echo")
        a, b = score_sbo(s["out"]), score_echo(e["out"])
        rej = sum(1 for w in s["warnings"] + e["warnings"] if w.startswith("AI-javítás elvetve"))
        out.append(f"| {m} | {len(a['fixed'])}/{n_err} | {len(a['wrong_fix'])} | {len(a['broken'])}/{n_ok} | "
                   f"{len(a['speaker_ok'])}/{n_spk} | {len(a['speaker_wrong'])} | {rej} | {len(b['flagged_ok'])}/{n_bad} | "
                   f"{len(b['false_flag'])} | {s['seconds']:.0f} s / {e['seconds']:.0f} s |")
        detail.append(f"\n## {m}\n")
        detail += [f"- javítva: {x}" for x in a["fixed"]] + [f"- NEM javítva: {x}" for x in a["not_fixed"]]
        detail += [f"- rosszul javítva: {x}" for x in a["wrong_fix"]] + [f"- helyes szó elrontva: {x}" for x in a["broken"]]
        detail += [f"- beszélő javítva: {x}" for x in a["speaker_ok"]] + [f"- beszélő NEM javítva: {x}" for x in a["speaker_missed"]]
        detail += [f"- beszélő elrontva: {x}" for x in a["speaker_wrong"]]
        detail += [f"- echo gyanús, jelölve: {x}" for x in b["flagged_ok"]] + [f"- echo gyanús, NEM jelölve: {x}" for x in b["missed"]]
        detail += [f"- echo téves jelölés: {x}" for x in b["false_flag"]]
        detail += [f"- figyelmeztetés: {w}" for w in s["warnings"] + e["warnings"]]
        if not (s["ran"] and e["ran"]):
            detail.append("- AZ ÁTNÉZÉS NEM FUTOTT LE (ld. figyelmeztetés)")
    return "\n".join(out + detail)


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
        (RESULTS / f"review_raw_{stamp}.json").write_text(json.dumps(records, ensure_ascii=False, indent=2),
                                                          encoding="utf-8")
    text = report(records)
    (RESULTS / f"review_report_{stamp}.md").write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
