"""Kitalált tesztbeteg a klinikai összefoglaló, az asszisztens és a kórtörténet méréséhez.

Minden adat kitalált. A dokumentumok szövegében a beteg neve már maszkolva áll ("[beteg]"), ahogy a Spring a
modellnek átadja. A tények szerkezete a Spring → docproc kérések alakja (summarize.render_facts,
chat.render_context, history.build).
"""

DOCUMENTS = [
    {"id": 1, "date": "2026-03-10", "type": "AMBULANCE", "text": """Szintetikus Megyei Kórház – Kardiológiai Szakrendelés
AMBULÁNS LAP
Név: [beteg]   TAJ: 000 000 000   Vizsgálat dátuma: 2026.03.10.

Anamnézis: 2018 óta ismert 2-es típusú diabetes mellitus, metforminnal kezelt. 2021-ben szívinfarktus miatt a jobb
koszorúéren PCI és stent beültetés történt. Dohányzást 2021-ben abbahagyta.
Jelen panasz: terhelésre, két emelet után jelentkező nehézlégzés, éjszakai köhögés.
Status: RR 128/76 Hgmm, P 72/min, szabályos. Bokaödéma nincs. Tüdő felett vezikuláris légzés.
Echokardiográfia: a bal kamra tágult, ejekciós frakció 32%.

Diagnózis: Krónikus szívelégtelenség csökkent ejekciós frakcióval (BNO: I50.9)
           2-es típusú diabetes mellitus (BNO: E11.9)
Vélemény, javaslat: Dapagliflozin 10 mg naponta bevezetése javasolt. Bisoprolol 5 mg, ramipril 5 mg folytatása.
Kontroll 3 hónap múlva laborral (kálium, kreatinin).
Dr. Teszt Orvos kardiológus"""},
    {"id": 2, "date": "2026-06-02", "type": "AMBULANCE", "text": """Szintetikus Megyei Kórház – Nefrológiai Szakrendelés
KONZÍLIUM
Név: [beteg]   Dátum: 2026.06.02.

Kérdés: csökkent vesefunkció, metformin adagolása.
Lelet: kreatinin 156 µmol/L, eGFR 38 mL/min/1,73m2, kálium 5,4 mmol/L. Vizelet: fehérjeürítés enyhén fokozott.
Magas vérnyomás 2015 óta ismert.
Diagnózis: Krónikus vesebetegség 3b stádium (BNO: N18.3)
Vélemény: A metformin adagja napi 2 x 500 mg-ra csökkentendő. Spironolakton mellett havonta kálium-ellenőrzés
javasolt. Nefrológiai kontroll fél év múlva.
Dr. Minta Nefrológus"""},
    {"id": 3, "date": "2026-09-20", "type": "AMBULANCE", "text": """Szintetikus Megyei Kórház – Sürgősségi Betegellátó Osztály
AMBULÁNS LAP
Név: [beteg]   Felvétel: 2026.09.20.

Panasz: három napja fokozódó nehézlégzés, mindkét oldali bokaödéma, éjszaka csak ülve tud aludni.
Status: RR 142/88 Hgmm, P 96/min, SpO2 93%. Mindkét bázison szörtyzörej. Kétoldali boka- és lábszárödéma.
Labor: kálium 5,8 mmol/L, NT-proBNP 4200 pg/mL.
Diagnózis: Szívelégtelenség dekompenzációja (BNO: I50.9)
           Hyperkalaemia (BNO: E87.5)
Terápia: furoszemid 40 mg iv. Ellátását követően otthonába bocsátjuk.
Javaslat: furoszemid adagja napi 80 mg-ra emelendő, a spironolakton a kálium normalizálódásáig szüneteltetendő.
Kardiológiai kontroll egy héten belül.
Dr. Próba Sürgősségi"""},
    {"id": 4, "date": "2026-09-21", "type": "AMBULANCE", "text": """Szintetikus Megyei Kórház – Kardiológia
ECHOKARDIOGRÁFIÁS LELET
Név: [beteg]   Dátum: 2026.09.21.

Vizsgálat eredménye:
Bal kamra végdiasztolés átmérő: 64 mm
Ejekciós frakció: 30 %
Vélemény: Tágult bal kamra, súlyosan csökkent bal kamrai funkció (EF 30%), közepes fokú mitralis regurgitáció.
Dr. Teszt Orvos"""},
]

# A kórtörténet aranystandardja: dokumentumonként a diagnózisok (szótő, BNO) és a dátumos korábbi események
HISTORY_GOLD = {
    1: {"diagnoses": [("szívelégtelenség", ["szívelégtelen", "szivelegtelen"], "I50.9"),
                      ("2-es típusú diabetes", ["diabetes", "cukorbeteg"], "E11.9")],
        "events": [("2018", ["diabetes", "cukorbeteg"]), ("2021", ["infarktus", "pci", "stent"])]},
    2: {"diagnoses": [("krónikus vesebetegség 3b", ["vesebeteg", "vese"], "N18.3")],
        "events": [("2015", ["vérnyomás", "hypert", "hipert"])]},
    3: {"diagnoses": [("szívelégtelenség dekompenzáció", ["szívelégtelen", "dekompenz"], "I50.9"),
                      ("hyperkalaemia", ["hyperkal", "hiperkal", "kálium"], "E87.5")], "events": []},
    4: {"diagnoses": [], "events": []},
}

LABS = [  # summarize.render_facts alakja: status = a referencia-szabály ítélete (None: normál)
    {"name": "Kálium", "unit": "mmol/L", "date": "2026-09-20", "value": "5,8", "status": "felett", "reference": "3,5–5,1"},
    {"name": "Kálium", "unit": "mmol/L", "date": "2026-06-02", "value": "5,4", "status": "felett", "reference": "3,5–5,1"},
    {"name": "Kreatinin", "unit": "µmol/L", "date": "2026-06-02", "value": "156", "status": "felett", "reference": "62–106"},
    {"name": "eGFR", "unit": "mL/min/1,73m2", "date": "2026-06-02", "value": "38", "status": "alatt", "reference": "> 60"},
    {"name": "NT-proBNP", "unit": "pg/mL", "date": "2026-09-20", "value": "4200", "status": "felett", "reference": "< 125"},
    {"name": "HbA1c", "unit": "%", "date": "2026-06-02", "value": "7,9", "status": "felett", "reference": "4,0–6,0"},
    {"name": "Nátrium", "unit": "mmol/L", "date": "2026-09-20", "value": "138", "status": None, "reference": "136–145"},
    {"name": "Hemoglobin", "unit": "g/L", "date": "2026-09-20", "value": "136", "status": None, "reference": "135–175"},
    {"name": "CRP", "unit": "mg/L", "date": "2026-09-20", "value": "3,1", "status": None, "reference": "< 5,0"},
]
MEDICATIONS = [
    {"id": 11, "name": "Ramipril", "activeIngredient": "ramipril", "dose": "5 mg", "route": "naponta 1x", "verified": True},
    {"id": 12, "name": "Bisoprolol", "activeIngredient": "bisoprolol", "dose": "5 mg", "route": "naponta 1x", "verified": True},
    {"id": 13, "name": "Spironolakton", "activeIngredient": "spironolactone", "dose": "25 mg", "route": "naponta 1x", "verified": True},
    {"id": 14, "name": "Furoszemid", "activeIngredient": "furosemide", "dose": "80 mg", "route": "naponta 1x", "verified": True},
    {"id": 15, "name": "Metformin", "activeIngredient": "metformin", "dose": "500 mg", "route": "naponta 2x", "verified": True},
    {"id": 16, "name": "Dapagliflozin", "activeIngredient": "dapagliflozin", "dose": "10 mg", "route": "naponta 1x", "verified": False},
]
ALLERGIES = [{"id": 21, "substance": "Penicillin"}]
STATEMENTS = [
    {"id": 31, "label": "Diagnózis", "text": "Krónikus szívelégtelenség csökkent ejekciós frakcióval", "date": "2026-03-10", "reviewStatus": "ACCEPTED"},
    {"id": 32, "label": "Diagnózis", "text": "Krónikus vesebetegség 3b stádium", "date": "2026-06-02", "reviewStatus": "ACCEPTED"},
    {"id": 33, "label": "Diagnózis", "text": "Szívelégtelenség dekompenzációja; hyperkalaemia", "date": "2026-09-20", "reviewStatus": "PENDING"},
    {"id": 34, "label": "Panasz", "text": "Három napja fokozódó nehézlégzés, kétoldali bokaödéma", "date": "2026-09-20", "reviewStatus": "PENDING"},
    {"id": 35, "label": "Vizsgálat", "text": "Echokardiográfia: ejekciós frakció 30%, közepes mitralis regurgitáció", "date": "2026-09-21", "reviewStatus": "PENDING"},
]
INTERACTIONS = [{"severity": "CRITICAL", "text": "Ramipril + Spironolakton: súlyos interakció (Ramipril – Spironolactone), hyperkalaemia kockázata. Forrás: DDInter 2.0"}]


def summary_facts() -> dict:
    return {"periodStart": "2026-03-27", "periodEnd": "2026-09-27", "ageYears": 68, "labs": LABS,
            "statements": STATEMENTS, "medications": MEDICATIONS, "allergies": ALLERGIES,
            "documents": DOCUMENTS, "recordings": [], "interactions": INTERACTIONS}


def chat_facts() -> list[dict]:
    out = []
    for i, l in enumerate(LABS):
        st = f" [{l['status']}]" if l["status"] else ""
        out.append({"kind": "LAB_RESULT", "id": 100 + i, "label": f"{l['name']} · {l['date']}",
                    "source": {"type": "LAB", "labResultId": 100 + i}, "abnormal": bool(l["status"]),
                    "text": f"{l['name']}: {l['value']} {l['unit']} (ref {l['reference']}){st}, {l['date']}"})
    for m in MEDICATIONS:
        out.append({"kind": "MEDICATION", "id": m["id"], "label": m["name"], "source": {"type": "MEDICATION"},
                    "text": f"Jelenleg aktív gyógyszer: {m['name']} ({m['activeIngredient']}) {m['dose']}, {m['route']}"
                            + ("" if m["verified"] else " [nem ellenőrzött]")})
    for a in ALLERGIES:
        out.append({"kind": "ALLERGY", "id": a["id"], "label": a["substance"], "source": {"type": "ALLERGY"},
                    "text": f"Allergia: {a['substance']}"})
    for s in STATEMENTS:
        out.append({"kind": "STATEMENT", "id": s["id"], "label": s["label"], "source": {"type": "STATEMENT"},
                    "text": f"[{s['label']}] {s['text']} ({s['date']})"})
    return out


# Az asszisztens kérdései: (kérdés, elvárt elemek szótővel, tilos állítások szótővel)
QUESTIONS = [
    ("Melyek a kóros laborértékei?",
     [("kálium", ["kálium", "kalium"]), ("kreatinin", ["kreatinin"]), ("eGFR", ["egfr"]),
      ("NT-proBNP", ["nt-probnp", "probnp"]), ("HbA1c", ["hba1c"])],
     [("nátrium kórosként", ["nátrium"]), ("hemoglobin kórosként", ["hemoglobin"]), ("CRP kórosként", ["crp"])]),
    ("Milyen gyógyszereket szed jelenleg, milyen adagban?",
     [("ramipril 5 mg", ["ramipril"]), ("bisoprolol 5 mg", ["bisoprolol"]), ("spironolakton 25 mg", ["spironol"]),
      ("furoszemid 80 mg", ["furoszemid", "furosemid"]), ("metformin 2x500 mg", ["metformin"]),
      ("dapagliflozin 10 mg", ["dapagliflozin"])], []),
    ("Van gyógyszerallergiája?", [("penicillin", ["penicillin"])], []),
    ("Mennyi volt az ejekciós frakció a legutóbbi echokardiográfián?", [("EF 30%", ["30"])], [("EF 32% mint legutóbbi", ["32"])]),
]

# A klinikai összefoglaló elvárt tartalma (szótő)
SUMMARY_GOLD = [
    ("szívelégtelenség", ["szívelégtelen"]), ("csökkent EF / 30%", ["ejekció", "ef", "30"]),
    ("krónikus vesebetegség", ["vesebeteg", "vesefunkc", "egfr"]), ("hyperkalaemia / kálium", ["kálium", "hyperkal", "hiperkal"]),
    ("diabetes", ["diabetes", "cukorbeteg"]), ("nehézlégzés / ödéma", ["nehézlégz", "ödéma", "oedema"]),
    ("echokardiográfia", ["echo", "echokard"]),
]
