"""B) A két újdonság igazolása a futó API-n, egy szintetikus tesztbetegen („Mérés Teszt”, TAJ nélkül).

(1) Forrásidézet-elutasítás: RETRIEVED_FACT szó szerinti idézettel (várt 2xx) és átfogalmazva (várt 400),
    DOCUMENT (text/plain) forrással 20+20, AUDIO (kézi JSON-felvétel, 10 megszólalás) forrással 10+10;
    + 5 kísérlet DETERMINISTIC_RULE létrehozására az API-n át (várt 400).
(2) Riasztás visszavonása: 10 laborvizsgálat tartományon kívül → majd egy újabb, tartományon belüli mérés;
    10 ismert interakciós gyógyszerpár (DDInter: 5 Major, 5 Moderate) → riasztás → az egyik leállítása → törlődik-e.
A végén a tesztbeteget törli (SQL, CASCADE; a feltöltött fájlt a lemezről is). --keep: nem törli.

Futtatás (a Spring fut, :8080):  python tools\\meresek_2026-11\\b_ujdonsagok.py
"""
import argparse
import json
import shutil
import time
from datetime import datetime, timedelta, timezone

from common import ENV, ROOT, api, multipart, save_json, sql, sql_exec

DOCTOR_ID = 3  # orvos szerepkörű felhasználó (X-User-Id)
H = {"X-User-Id": str(DOCTOR_ID)}

# Kitalált ambuláns lap — minden mondat egy idézhető tény
SENTENCES = [
    "A beteg 3 napja észlel nehézlégzést.",
    "A vérnyomása 150/95 Hgmm volt a felvételkor.",
    "A pulzusszám 104/perc, szabálytalan.",
    "A testhőmérséklet 37,8 fok.",
    "Az oxigénszaturáció 91 százalék szobalevegőn.",
    "Ismert magasvérnyomás-betegség miatt ramiprilt szed.",
    "Penicillinre kiütéssel reagált korábban.",
    "A mellkasröntgen bal oldali alsó lebenyben beszűrődést mutat.",
    "A hasi ultrahang eltérést nem igazolt.",
    "A nyaki vénák nem teltebbek.",
    "Bokaödéma mindkét oldalon látható.",
    "A beteg dohányzik, napi egy doboz cigarettát szív.",
    "Alkoholt alkalmanként fogyaszt.",
    "Az EKG-n pitvarfibrilláció látható.",
    "A hasa puha, betapintható, nyomásérzékenység nincs.",
    "Kardiológiai konzílium javasolt.",
    "Antibiotikum-kezelést kezdtünk.",
    "A kontrollvizsgálat egy hét múlva esedékes.",
    "A beteg a kezeléssel egyetért.",
    "Az állapota stabil, otthonába bocsátható.",
]
# átfogalmazás: (kategória, szöveg) — ugyanarra a karaktertartományra hivatkozva
PARAPHRASES = [
    ("szám", "A beteg 4 napja észlel nehézlégzést."),
    ("szám", "A vérnyomása 140/95 Hgmm volt a felvételkor."),
    ("szám", "A pulzusszám 140/perc, szabálytalan."),
    ("szám", "A testhőmérséklet 38,7 fok."),
    ("szám", "Az oxigénszaturáció 97 százalék szobalevegőn."),
    ("szinonima", "Ismert hipertónia miatt ramiprilt szed."),
    ("szinonima", "Penicillinre bőrkiütéssel reagált korábban."),
    ("szinonima", "A mellkasröntgen bal oldali alsó lebenyben infiltrátumot mutat."),
    ("szinonima", "A hasi UH eltérést nem igazolt."),
    ("szinonima", "A nyaki vénák nem telődnek jobban."),
    ("szórend", "Mindkét oldalon bokaödéma látható."),
    ("szórend", "A beteg dohányzik, napi egy doboz cigarettát szív el."),
    ("szórend", "Alkalmanként fogyaszt alkoholt."),
    ("szórend", "Pitvarfibrilláció látható az EKG-n."),
    ("szórend", "A hasa puha, betapintható, nincs nyomásérzékenység."),
    ("kihagyás", "Konzílium javasolt."),
    ("kihagyás", "Antibiotikumot kezdtünk."),
    ("kihagyás", "A kontrollvizsgálat egy hét múlva."),
    ("kihagyás", "A beteg egyetért."),
    ("kihagyás", "Az állapota stabil."),
]
UTTERANCES = [
    ("DOCTOR", "Jó napot, mi szél hozta ma ide?"),
    ("PATIENT", "Két napja erős fejfájásom van, főleg reggel."),
    ("DOCTOR", "Szed valamilyen gyógyszert rendszeresen?"),
    ("PATIENT", "Vérnyomásra napi egy amlodipint szedek."),
    ("DOCTOR", "Van valamilyen allergiája?"),
    ("PATIENT", "Az ibuprofentől bedagad a szemem."),
    ("DOCTOR", "A vérnyomása most 165 per 100."),
    ("PATIENT", "Otthon általában 130 körül van."),
    ("DOCTOR", "Kérek egy laborvizsgálatot és egy szemészeti konzíliumot."),
    ("DOCTOR", "Holnap reggel jöjjön vissza kontrollra."),
]
AUDIO_PARAPHRASES = [
    ("szinonima", "Jó napot, mi a panasza ma?"),
    ("szám", "Három napja erős fejfájásom van, főleg reggel."),
    ("kihagyás", "Szed gyógyszert rendszeresen?"),
    ("szám", "Vérnyomásra napi két amlodipint szedek."),
    ("szórend", "Allergiája van valamilyen?"),
    ("szinonima", "Az ibuprofentől megdagad a szemem."),
    ("szám", "A vérnyomása most 156 per 100."),
    ("szórend", "Otthon 130 körül van általában."),
    ("kihagyás", "Kérek egy laborvizsgálatot."),
    ("szinonima", "Holnap reggel jöjjön vissza ellenőrzésre."),
]
# 10 laborvizsgálat (kitalált nevek nem kellenek: az egységes név csak csoportosít)
LABS = [("Nátrium", "mmol/L", 136, 145, 128, 140), ("Kálium", "mmol/L", 3.5, 5.1, 6.2, 4.4),
        ("Kreatinin", "umol/L", 62, 106, 180, 90), ("Glükóz", "mmol/L", 3.9, 6.1, 11.4, 5.2),
        ("Hemoglobin", "g/L", 135, 175, 102, 141), ("Fehérvérsejt", "G/L", 4.0, 10.0, 15.3, 7.1),
        ("Thrombocyta", "G/L", 150, 400, 92, 210), ("CRP", "mg/L", 0, 5, 48, 3.1),
        ("ALT", "U/L", 0, 50, 132, 30), ("TSH", "mU/L", 0.35, 4.94, 9.8, 1.6)]
# 10 ismert pár a helyi DDInter-táblából (ld. a jelentést): 5 Major, 5 Moderate
PAIRS = [("Warfarin", "Fluconazole", "Major"), ("Simvastatin", "Clarithromycin", "Major"),
         ("Sildenafil", "Nitroglycerin", "Major"), ("Clopidogrel", "Omeprazole", "Major"),
         ("Methotrexate", "Trimethoprim", "Major"), ("Acetylsalicylic acid", "Ramipril", "Moderate"),
         ("Allopurinol", "Warfarin", "Moderate"), ("Amiodarone", "Bisoprolol", "Moderate"),
         ("Acetylsalicylic acid", "Digoxin", "Moderate"), ("Acetylsalicylic acid", "Amlodipine", "Moderate")]


def ddi_level(a: str, b: str) -> str | None:
    r = sql(f"""select i.level from ddi_interaction i join ddi_drug x on x.id = i.drug_a join ddi_drug y on y.id = i.drug_b
                where (lower(x.name), lower(y.name)) in (('{a.lower()}', '{b.lower()}'), ('{b.lower()}', '{a.lower()}'))""")
    return r[0]["level"] if r else None


def err(resp) -> str:
    try:
        j = resp.json()
        return (j.get("detail") or j.get("message") or j.get("error") or str(j))[:160] if isinstance(j, dict) else str(j)[:160]
    except Exception:
        return resp.body[:160].decode("utf-8", "replace")


def model_producer():
    return {"type": "EXTRACTOR", "extractorName": "meres-2026-11", "extractorVersion": "1"}


def rule_statements(pid: int, rule: str) -> list[dict]:
    return sql(f"select id, severity, source from statement where patient_id = {pid} and producer->>'ruleId' = '{rule}'")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true", help="a tesztbeteget nem törli")
    args = ap.parse_args()
    out = {"date": datetime.now().isoformat(timespec="seconds")}

    r = api("POST", "/api/patients", {"name": "Mérés Teszt", "taj": None, "motherName": "Teszt Anya",
                                      "birthDate": "1970-01-01", "address": None}, headers=H)
    assert r.status == 201, r
    pid = r.json()["id"]
    out["patient_id"] = pid
    print("tesztbeteg:", pid)
    try:
        # ---------- (1) DOCUMENT forrás ----------
        text = "\n".join(SENTENCES) + "\n"
        body, ct = multipart({"patientId": pid, "type": "AMBULANCE", "clinicalDate": "2026-11-15"},
                             {"file": ("meres.txt", text.encode("utf-8"), "text/plain")})
        r = api("POST", "/api/documents", raw=body, content_type=ct, headers=H)
        assert r.status in (200, 201, 202), r
        doc = r.json()
        if not doc.get("hasExtractedText"):
            r2 = api("PUT", f"/api/documents/{doc['id']}/text", {"text": text}, headers=H)
            assert r2.status == 200, r2
        offsets, pos = [], 0
        for s in SENTENCES:
            i = text.index(s, pos)
            offsets.append((i, i + len(s)))  # ASCII-n kívüli betű is 1 code point (BMP)
            pos = i + len(s)

        def stmt(t, src):
            return api("POST", "/api/statements", {"patientId": pid, "type": "RETRIEVED_FACT", "text": t,
                                                   "label": "Mérés", "severity": "INFO", "source": src,
                                                   "producer": model_producer()}, headers=H)
        doc_res = {"verbatim": [], "paraphrase": []}
        for s, (a, b) in zip(SENTENCES, offsets):
            r = stmt(s, {"type": "DOCUMENT", "documentId": doc["id"], "charStart": a, "charEnd": b})
            doc_res["verbatim"].append({"status": r.status, "err": None if r.status < 300 else err(r)})
        for (cat, p), (a, b) in zip(PARAPHRASES, offsets):
            r = stmt(p, {"type": "DOCUMENT", "documentId": doc["id"], "charStart": a, "charEnd": b})
            doc_res["paraphrase"].append({"cat": cat, "status": r.status, "err": None if r.status < 300 else err(r)})
        out["document_quotes"] = doc_res

        # ---------- (1) AUDIO forrás (kézi JSON-felvétel) ----------
        r = api("POST", "/api/recordings", {"patientId": pid, "userId": DOCTOR_ID, "mode": "SBO",
                                            "audioPath": "meres/szintetikus.wav", "asrModel": "nincs (szintetikus)"},
                headers=H)
        audio_res = {"setup": r.status}
        if r.status == 201:
            rid = r.json()["id"]
            r = api("PATCH", f"/api/recordings/{rid}/status", {"status": "PROCESSING", "durationSec": 60}, headers=H)
            utt = [{"turnId": i, "speaker": sp, "startMs": i * 5000, "endMs": i * 5000 + 4000, "text": t}
                   for i, (sp, t) in enumerate(UTTERANCES)]
            r2 = api("POST", f"/api/recordings/{rid}/utterances", {"utterances": utt}, headers=H)
            r3 = api("PATCH", f"/api/recordings/{rid}/status", {"status": "DONE"}, headers=H)
            audio_res["setup"] = [r.status, r2.status, r3.status]
            audio_res["verbatim"], audio_res["paraphrase"] = [], []
            for i, (_, t) in enumerate(UTTERANCES):
                r = stmt(t, {"type": "AUDIO", "recordingId": rid, "turnIds": [i]})
                audio_res["verbatim"].append({"status": r.status, "err": None if r.status < 300 else err(r)})
            for i, (cat, p) in enumerate(AUDIO_PARAPHRASES):
                r = stmt(p, {"type": "AUDIO", "recordingId": rid, "turnIds": [i]})
                audio_res["paraphrase"].append({"cat": cat, "status": r.status, "err": None if r.status < 300 else err(r)})
        else:
            audio_res["err"] = err(r)
        out["audio_quotes"] = audio_res

        # ---------- (2) labor: tartományon kívül, majd vissza ----------
        now = datetime.now(timezone.utc).replace(microsecond=0)
        t1, t2 = (now - timedelta(days=2)).isoformat(), (now - timedelta(days=1)).isoformat()
        rows1 = [{"name": n, "unit": u, "refLow": lo, "refHigh": hi, "value": bad, "measuredAt": t1}
                 for n, u, lo, hi, bad, _ in LABS]
        r = api("POST", "/api/lab-results", {"patientId": pid, "documentId": None, "results": rows1}, headers=H)
        lab = {"post_out_of_range": r.status}
        after1 = rule_statements(pid, "lab-reference-range")
        lab["alerts_after_out_of_range"] = len(after1)
        lab_ids = {s["source"]["labResultId"] for s in after1}
        per_test = []
        for n, u, lo, hi, _, good in LABS:
            r = api("POST", "/api/lab-results", {"patientId": pid, "documentId": None, "results": [
                {"name": n, "unit": u, "refLow": lo, "refHigh": hi, "value": good, "measuredAt": t2}]}, headers=H)
            per_test.append(r.status)
        after2 = rule_statements(pid, "lab-reference-range")
        lab["post_in_range"] = per_test
        lab["alerts_after_in_range"] = len(after2)
        lab["alerts_retracted"] = len(lab_ids - {s["source"]["labResultId"] for s in after2})
        out["lab_retraction"] = lab

        # ---------- (2) gyógyszer-interakció: pár → riasztás → leállítás ----------
        drugs = []
        for a, b, expected in PAIRS:
            rec = {"pair": f"{a} + {b}", "ddinter_level": ddi_level(a, b), "expected": expected}
            ids = []
            for name in (a, b):
                r = api("POST", "/api/medications", {"patientId": pid, "name": name, "activeIngredient": name},
                        headers=H)
                ids.append(r.json()["id"] if r.status == 201 else None)
                rec.setdefault("create", []).append(r.status)
            alerts = [s for s in rule_statements(pid, "drug-interaction")
                      if sorted(s["source"]["medicationIds"]) == sorted(i for i in ids if i)]
            rec["alert"] = len(alerts)
            rec["severity"] = alerts[0]["severity"] if alerts else None
            r = api("POST", f"/api/medications/{ids[1]}/discontinue", headers=H) if ids[1] else None
            rec["discontinue"] = r.status if r else None
            left = [s for s in rule_statements(pid, "drug-interaction")
                    if sorted(s["source"]["medicationIds"]) == sorted(i for i in ids if i)]
            rec["alert_after_discontinue"] = len(left)
            if ids[0]:
                api("POST", f"/api/medications/{ids[0]}/discontinue", headers=H)
            drugs.append(rec)
            print(rec)
        out["drug_retraction"] = drugs

        # ---------- (1) DETERMINISTIC_RULE az API-n át ----------
        lab_id = sql(f"select id from lab_result where patient_id = {pid} limit 1")[0]["id"]
        med_ids = [m["id"] for m in sql(f"select id from medication where patient_id = {pid} order by id limit 2")]
        rule = {"type": "RULE", "ruleId": "lab-reference-range", "ruleVersion": "1"}
        attempts = [
            ("DOCUMENT forrás, RULE előállító", {"type": "DOCUMENT", "documentId": doc["id"],
                                                 "charStart": offsets[0][0], "charEnd": offsets[0][1]}, rule),
            ("LAB forrás (mint a szabálymotor)", {"type": "LAB", "labResultId": lab_id}, rule),
            ("MEDICATION forrás (mint az interakció-szabály)", {"type": "MEDICATION", "medicationIds": med_ids},
             {"type": "RULE", "ruleId": "drug-interaction", "ruleVersion": "1"}),
            ("AUDIO forrás", {"type": "AUDIO", "recordingId": sql(
                f"select id from recording where patient_id = {pid} limit 1")[0]["id"] if audio_res.get("verbatim") else 0,
                "turnIds": [0]}, rule),
            ("MODEL előállító (álcázott LLM-kimenet)", {"type": "LAB", "labResultId": lab_id},
             {"type": "MODEL", "modelName": "medgemma:latest", "seed": 1, "promptHash": "x"}),
        ]
        rule_res = []
        for name, src, prod in attempts:
            r = api("POST", "/api/statements", {"patientId": pid, "type": "DETERMINISTIC_RULE", "text": SENTENCES[0],
                                                "label": "Mérés", "severity": "WARNING", "source": src,
                                                "producer": prod}, headers=H)
            rule_res.append({"attempt": name, "status": r.status, "err": None if r.status < 300 else err(r)})
        out["rule_via_api"] = rule_res
    finally:
        if not args.keep:
            sql_exec(f"delete from pacient where id = {pid};")
            docs_dir = ROOT / ENV.get("MEDIAI_DOCUMENTS_DIR", "backend/data/documents") / str(pid)
            for d in (docs_dir, ROOT / "data" / "documents" / str(pid), ROOT / "backend" / "data" / "documents" / str(pid)):
                if d.exists():
                    shutil.rmtree(d)
                    out.setdefault("deleted_dirs", []).append(str(d.relative_to(ROOT)))
            out["patient_deleted"] = not sql(f"select id from pacient where id = {pid}")
    save_json("b_ujdonsagok.json", out)
    print(json.dumps({k: v for k, v in out.items() if k != "drug_retraction"}, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
