# MediAI — mérések az IDK-előadáshoz (2026. november)

Dátum: **2026-11-15**, fejlesztői gép (Windows 11, RTX 5060 Laptop 8 GB, Ollama: `medgemma:latest` = MedGemma 4B
Q4_K_M, `medgemma:27b`). Backend `docproc-18` / `voice-1`. A jelentésben **csak összesített szám** szerepel: betegnév,
TAJ, leletszöveg, dokumentumtartalom nincs benne. A nyers, szintén csak összesítő vagy szintetikus adatot tartalmazó
fájlok: `tools/meresek_2026-11/eredmenyek/`. Minden parancs a repó gyökeréből futtatandó. Új Flyway-migráció nem készült.

| Mérés | Minta | Futás | Eredmény röviden |
|---|---|---|---|
| A) Valós leletek | 27 egyedi PDF (190 feldolgozás), 1831 laborsor | 1 (DB-állapot) | A laborsorok 99,0%-a szó szerint visszakereshető a lelet szövegében |
| B1) Forrásidézet-elutasítás | 40 DOCUMENT + 20 AUDIO + 5 szabály-kísérlet | 1 | 65/65 a várt válasz |
| B2) Riasztás visszavonása | 10 labor + 10 gyógyszerpár | 1 | 10/10 és 10/10 |
| C) Ismételhetőség | 4. mérés (4B: 3 seed × 3, 27B: 3 seed × 1), 2. mérés (3 seed), 8. mérés (3 seed) | 9 + 3 + 3 + 3 | Az átnézés kimenete minden futásban azonos (egyezési arány 100%) |
| D) Hibatűrés | 3 leállítás (Ollama, voice, docproc) | 1–1 | docproc és voice: magától folytatja; Ollama: FAILED, 1 kattintással újra |
| E) Tárhely | 8 hangfelvétel (352 s), 27 PDF | — | ≈ 1,26 TB/év (128 kbit/s) → ≈ 0,38 TB/év 32 kbit/s-mal (bevezetve) |
| F) WER | 5 felolvasott szöveg × 2 felolvasás, 838 szó, 1 beszélő (echo) | 2 felolvasás + 1 újrafuttatás | WER 18–21% (normalizált), írásmód-függetlenül 14–16%, CER 2,5–3,1%; az átnézés nem javított |
| G) Az orvos terhe | 16 beteg, 1077 állítás | 1 (DB-állapot) | az állítások 98,1%-a döntés nélkül marad, a munka ettől nem áll le |

---

## A) Valós leleteken végzett próba (TRL 4 indoklása)

**Mi ez:** a DB-ben lévő összes dokumentum Szabi saját, valós EESZT-lelete. Ugyanazt a 22–23 fájlt több
tesztbetegnél is feltöltöttük, ezért **190 dokumentumsor = 27 egyedi PDF** (16 beteg). Az egyedi fájlok típusa:
**10 LAB, 17 AMBULANCE, 0 IMAGING**. Modell: `medgemma:latest` (4B), seed 42.

```
python tools\meresek_2026-11\a_valos_leletek.py
```

| Mutató | Érték |
|---|---|
| Feldolgozások (dokumentumsor) | 190, ebből **PROCESSED 176 (92,6%)** (LAB 65, AMBULANCE 111), **FAILED 14** — a 2026-11-15-i újrafeldolgozás után (előtte 170/190, ld. lent) |
| A 14 FAILED oka | 10: a feldolgozó szolgáltatás nem futott; 4: Ollama-hívás hiba (500 / kapcsolat). Mind infrastruktúra-hiba, nem a kinyerésé; újrafeldolgozással pótolható. A korábbi 6 „duplicate element” hiba megszűnt (ld. lent) |
| Feldolgozási idő, feltöltéstől a kész állapotig (az első feldolgozásé) | medián **406 s** (n = 169; min 6 s, max 20,7 óra: ekkor nem futott a szolgáltatás; a sorban állást is tartalmazza, mert a leleteket egyszerre töltöttük fel) |
| Egy dokumentum tényleges ideje (két egymást követő elkészülés között, ugyanannál a betegnél) | medián **34 s**, p10–p90 **11–181 s** (n = 173) |
| Kinyert laborsor | **1831** (számos 1541, cenzorált `<`/`>` 61, szöveges 229) |
| Laborsor PDF-beli hellyel (`source_page` nem NULL) | **1179 / 1831 = 64,4%** |
| Kinyert gyógyszer | **55** (mind `DOCUMENT_PIPELINE`; 28 aktív, 27 leállított: a régebbi adag) |
| Kinyert allergia | 0 (a leletekben nem szerepel allergia) |
| Referenciatartomány-szabály riasztásai | **144** (WARNING) |
| Gyógyszer-interakció riasztásai | 10 (3 CRITICAL, 3 WARNING, 4 INFO) |

**Automatikus szó szerinti ellenőrzés** (minden laborsor: a név és az érték megtalálható-e a lelet kinyert
szövegében; kisbetű, egységes szóköz, tizedesvessző = tizedespont, a cenzorálás jele és a szöveges érték is):

| Ellenőrzés | Sor | Arány |
|---|---|---|
| Érték szó szerint a szövegben | 1822 / 1831 | 99,5% |
| Név szó szerint | 1637 / 1831 | 89,4% |
| Név + érték szó szerint | 1628 / 1831 | 88,9% |
| Név + érték, az érték a név utáni 250 karakteren belül | 1616 / 1831 | 88,3% |
| **Név (a kinyerés által hozzáfűzött záró zárójeles minősítő nélkül) + érték = „ellenőrizhető sor”** | **1813 / 1831** | **99,0%** |

A szigorú és a lazított névegyezés közti különbség szinte teljes egészében abból adódik, hogy a modell a
vizeletvizsgálat sorainak nevéhez „(vizelet)” minősítőt fűz (pl. „Fehérje” → „Fehérje (vizelet)”). Ez a név
pontosítása, nem kitalált sor. A maradék 18 sorból 9 egy csonkolt név, 9 pedig egy elválasztott szöveges érték.
Típus szerint, szigorú névvel: számos 93,8%, cenzorált 68,9%, szöveges 61,1%.

**Ismételt feldolgozás:** ugyanannak a fájlnak az azonos kinyerő-verzióval (`extractor_version`) végzett
feldolgozásai **10/10 csoportban ugyanannyi laborsort** adtak (26 feldolgozás). Verziók között 3/9 fájlnál
egyezett: a kinyerés a fejlesztés során (`docproc-5` → `-18`) változott.

**Kézi ellenőrzés:** `tools/meresek_2026-11/kezi_ellenorzes.csv`, 30 véletlen laborsor (seed 20261115), az
oszlopok: dokumentum id, sor neve, érték, mértékegység, referencia, helyes? (üres). **A fájl valós leletadatot
tartalmaz: gitignore-ban van, a gépen marad.** Kitöltés után: helyes sorok / 30 → a dia lábjegyzetébe.

**A 6 „duplicate element” hiba (javítva, 2026-11-15):** a 6 dokumentum (mind 2026-10-24, 14:02–14:14) azért lett
FAILED, mert a gyógyszer alkalmazási módjának felismerése (`drug/service/DrugRoutes.java`) `Set.of`-ot használt. Ez
kivételt dob, ha a gyógyszer szövegében egy szó kétszer szerepel („Bisoprolol … bisoprolol”, „SOFTACORT … softacort”,
„Tobrex … Tobrex”). Akkor a hiba a teljes mentést visszagörgette. Ugyanaznap 16:47-kor a `7549dff` commit `HashSet`-re
cserélte, az interakció-szabály köré pedig try/catch került: a szabály hibája azóta nem akaszthatja meg a gyógyszer
mentését. A mai kódban tehát a hiba már nem állt fenn. Pótlások:
- **regressziós teszt:** `backend/src/test/java/bme/mediai/drug/service/DrugRoutesTest.java` (2 teszt, zöld);
- **a 6 dokumentum újrafeldolgozva:** mind PROCESSED;
- az egyiknél (101) a PDF hiányzott a lemezről; ugyanaz a fájl (azonos SHA-256) megvolt más tesztbetegnél, onnan
  másoltam vissza, majd ellenőriztem a hash-t.

A laborsorok száma nem változott (a 6 mind ambuláns lap), a kézi ellenőrzés CSV-je is azonos maradt.

**Diára:** „27 valós (saját) EESZT-lelet, 176/190 sikeres feldolgozás (a többi infrastruktúra-hiba) · 1831 laborsor, ebből 99,0% szó szerint
visszakereshető a leletben · 64% a PDF-ben kiemelve · 144 referenciatartomány-riasztás · azonos verzión ismételve
10/10 azonos”.

---

## B) A két újdonság igazolása (futó API, szintetikus tesztbeteg)

Tesztbeteg: „Mérés Teszt”, TAJ nélkül, a mérés végén törölve (a DB-ből CASCADE, a lemezről a feltöltött fájl is).
Orvos: `X-User-Id: 3`. A backendhez **nem volt teszt** ezekre (a `backend/src/test` alatt csak a
DocumentProcessingResume-, VoiceLostJobs-, ReadOnlyMode-, ResearchLostJobs- és OutboundQueryGate-teszt van),
ezért a bizonyíték ez a mérőszkript, nem új JUnit-teszt.

```
python tools\meresek_2026-11\b_ujdonsagok.py
```

### B1) Forrásidézet szerveroldali elutasítása

| Eset | Elfogadva (201) | Elutasítva (400) | Várt |
|---|---|---|---|
| DOCUMENT forrás, szó szerinti idézet | **20** | 0 | elfogadva |
| DOCUMENT, átfogalmazva — szinonima (5), szórend (5), kihagyott szó (5), megváltoztatott szám (5) | 0 | **20** | elutasítva |
| AUDIO forrás (kézi JSON-felvétel, 10 megszólalás), szó szerinti | **10** | 0 | elfogadva |
| AUDIO, átfogalmazva — szinonima 3, szám 3, szórend 2, kihagyás 2 | 0 | **10** | elutasítva |
| `DETERMINISTIC_RULE` az API-n át (DOCUMENT / LAB / MEDICATION / AUDIO forrás, ill. MODEL előállítóval álcázva) | 0 | **5** | elutasítva |
| **Összesen** | 30/30 | 35/35 | **65/65 a várt válasz** |

Az elutasítás üzenete: „RETRIEVED_FACT szövege nem szerepel szó szerint … ha átfogalmazott, MODEL_INFERENCE-ként kell
rögzíteni”, ill. „Szabály-alapú állítást csak a szabálymotor hozhat létre, az API nem”. A szó szerinti összevetés
tervezetten kis- és nagybetűre, valamint szóközre érzéketlen: ilyen eltérést nem tekintünk átfogalmazásnak.

### B2) Riasztás visszavonása

| Eset | Keletkezett szabály-állítás | Törlődött a feloldás után | Várt |
|---|---|---|---|
| Labor: 10 vizsgálat tartományon kívül (Na, K, kreatinin, glükóz, Hb, fvs, thr, CRP, ALT, TSH), majd egy-egy újabb, tartományon belüli mérés | **10** | **10** | 10/10 |
| Gyógyszer: 10 DDInter-pár (5 Major → CRITICAL, 5 Moderate → WARNING), majd az egyik leállítása | **10** (súlyosság 10/10 helyes) | **10** | 10/10 |

Párok: warfarin + flukonazol, szimvasztatin + klaritromicin, szildenafil + nitroglicerin, klopidogrél + omeprazol,
metotrexát + trimetoprim (Major); acetilszalicilsav + ramipril, allopurinol + warfarin, amiodaron + bizoprolol,
acetilszalicilsav + digoxin, acetilszalicilsav + amlodipin (Moderate).

**Diára:** „Forrásidézet: 30/30 szó szerinti elfogadva, 30/30 átfogalmazás elutasítva, 5/5 API-n át küldött
»szabály« elutasítva · Visszavonás: labor 10/10, gyógyszer 10/10”.

---

## C) Ismételhetőség és a hangátnézés determinizmusa

**Kódváltozás (minimális, alapból változatlan viselkedés):** a hangátnézés Ollama-hívása eddig
`temperature: 0`-val, de **seed nélkül** ment (a 0,3-as hőmérsékletű második próba csak érvénytelen JSON esetén fut).
- `ai-service/voice/beszed_pipeline.py`: a `_call_ollama` új, opcionális `seed` paramétert kapott; ha meg van adva, az
  `options.seed`-be kerül, különben a kérés ugyanaz, mint eddig.
- `ai-service/voice/review.py`: `VOICE_REVIEW_SEED` környezeti változó (üresen nincs seed = a régi viselkedés); a
  `_ask` ezt adja át mindkét próbánál.

Mérőkörnyezet: a 2026-10-27-i `ai-service/eval` szkriptjei (aranystandard és pontozás változatlan), a termék kódját
hívva. Seedek: 1, 42, 1234.

```
ai-service\voice\ai_env\Scripts\python tools\meresek_2026-11\c_ismetelhetoseg.py review --models medgemma:latest --runs 3
ai-service\voice\ai_env\Scripts\python tools\meresek_2026-11\c_ismetelhetoseg.py review --models medgemma:27b --runs 1
ai-service\docproc\.venv\Scripts\python tools\meresek_2026-11\c_ismetelhetoseg.py lab
ai-service\voice\ai_env\Scripts\python tools\meresek_2026-11\c_ismetelhetoseg.py findings
ai-service\docproc\.venv\Scripts\python tools\meresek_2026-11\c_ismetelhetoseg.py report
```

### 4. mérés — hangátnézés (kitalált átirat: 5 beültetett szóhiba, 7 helyes bizonytalan szó, 2 beszélőcsere, 2 lehetetlen echo-érték)

| Modell | Futás | Helyes javítás összesen (szó + beszélő + echo, max. 9) | Szóhiba javítva /5 | Rosszul javítva | Helyes szó elrontva /7 | Beszélő /2 | Echo gyanús /2 | Idő / futás |
|---|---|---|---|---|---|---|---|---|
| medgemma:27b | 3 seed × 1 | **6** (6–6) | 3 (3–3) | 0 | 0 | 1 (1–1) | 2 (2–2) | 334 s (324–340) |
| medgemma:latest (4B) | 3 seed × 3 | **1** (1–1) | 0 (0–0) | 5 | 4 | 1 (1–1) | 0 (0–0) | 30 s (29–34) |

| Egyezési arány (a teljes kimenet: minden megszólalás szövege, beszélője, echo-jelölése azonos) | azonos seeden belül | seedek között |
|---|---|---|
| medgemma:latest (4B) | **9/9 pár = 100%** | **27/27 pár = 100%** |
| medgemma:27b | — (seedenként 1 futás) | **3/3 pár = 100%** |

A 27B-nél a futásidő (≈ 5,5 perc / futás, SBO + echo) miatt seedenként egy futás készült, a feladat szerint.
Mindkét modell eredménye **pontosan megegyezik a 2026-10-27-i 4. méréssel** (4B: 0/5, 5 rossz javítás, 4 elrontott
szó, 1/2 beszélő, 0/2 echo; 27B: 3/5, 0, 0, 1/2, 2/2), tehát egy hónap és egy seed nélküli futás után is ugyanaz.
**Következtetés:** `temperature: 0` mellett az átnézés ezen a gépen gyakorlatilag determinisztikus, a seed nem
változtat a kimeneten. A 4B gyenge eredménye a modell minősége, nem a véletlen műve. Fenntartás: az azonosság csak
azonos modellváltozat, Ollama-verzió és GPU mellett várható.

### 2. mérés — PDF-laborkinyerés (4B, 3 szintetikus lelet, 92 elvárt sor)

| Seed | Felidézés (helyes érték) | Precizitás | Rossz érték | PDF-kiemelés | Idő (3 lelet) |
|---|---|---|---|---|---|
| 1 | 92/92 | 92/92 | 0 | 80/92 | 111 s |
| 42 | 92/92 | 92/92 | 0 | 80/92 | 105 s |
| 1234 | 92/92 | 92/92 | 0 | 80/92 | 106 s |
| **átlag (min–max)** | **92 (92–92)** | 92 | 0 | 80 (80–80) | |

Azonosság: a 3 leletből 2 bitre azonos minden seednél; a harmadikon 1 sor mértékegység-szövege tér el
(„mL/min/1,73m2” ↔ „L/mL/min/1,73m2”), a pontozott mezők nem. → 91/92 sor teljesen azonos.

### 8. mérés — a felvétel hat csoportja (4B, 3 kitalált SBO-párbeszéd, 39 elvárt elem)

| Seed | Felidézés | Kinyert elem | Ebből elvárthoz illeszkedik | Idő (3 eset) |
|---|---|---|---|---|
| 1 / 42 / 1234 | 31/39 = 79% mindháromnál | 26 | 26/26 | 37–39 s |

Azonosság: a 3 esetből **3/3 bitre azonos** minden seednél (ugyanaz, mint a 10-27-i mérés: 31/39, 26/26).

**Diára:** „Ismételhetőség: 3 seed × 3 futás — hangátnézés egyezési aránya 100% (4B: 36/36, 27B: 3/3 pár), labor
92/92 mindhárom seeddel (terjedelem 0), csoportok 31/39 mindhárom seeddel (terjedelem 0)”.

---

## D) Hibatűrés

Szintetikus tesztbeteg („Mérés Teszt D”, a végén törölve), az `ai-service/eval` szintetikus PDF-leletei. A hanghoz egy
meglévő, 57 s-os tesztfelvétel (sbo, felolvasott kitalált párbeszéd) hangfájljának másolata kellett. A szkript port
szerint állítja le a folyamatot (`taskkill /T /F`), és ugyanúgy indítja újra, mint a `start.txt`. Egy-egy futás.

```
python tools\meresek_2026-11\d_hibaturo.py            # vagy: --only D1 / D2 / D3
```

| Eset | Beavatkozás | Várt viselkedés | Tényleges viselkedés | Helyreállás |
|---|---|---|---|---|
| **D1 Ollama** | PDF-feldolgozás közben (a docproc már a modellt hívja) az Ollama leáll; 30 s múlva újraindul | a dokumentum ne vesszen el, a hiba látsszon | 2,2 s alatt **FAILED**, a hiba szövegével („Az Ollama-hívás sikertelen: … Connection broken”). **Automatikus újrapróbálás nincs:** a FAILED végállapot, és a percenkénti `DocumentProcessingResume` csak a PROCESSING dokumentumot adja át újra. A felületen az „Újrafeldolgozás” (`POST /api/documents/{id}/reprocess`) kell. | kézi újrafeldolgozás után **57 s** alatt PROCESSED (a teljes kiesés a leállítástól ≈ 90 s) |
| **D3 docproc** | PDF-feldolgozás közben a docproc (:8001) leáll; 20 s múlva újraindul | a dokumentum PROCESSING marad, az újraindult szolgáltatás folytatja | a dokumentum **PROCESSING maradt** (nem FAILED). Az újraindulás után a Spring `DocumentProcessingResume` (percenként) **magától újra átadta**, és elkészült. Beavatkozás nem kellett. | az újraindítástól **117 s** (≤ 60 s várakozás a következő körre + ≈ 60 s feldolgozás) |
| **D2 voice service** | hangfelvétel-feldolgozás közben (ASR fut) a voice service (:8002) leáll; 20 s múlva újraindul | a felvétel PROCESSING marad, a `VoiceLostJobs` újra átadja (a hang megmaradt) | a felvétel **PROCESSING maradt**. Az újraindulás (a modellek betöltése 12 s) után **30 s-mal a VoiceLostJobs újra átadta**, és DONE lett (13 megszólalás); utána automatikusan elindult a csoport-kinyerés is. Beavatkozás nem kellett. | az újraindítástól **370 s** (≈ 30 s az átadásig + ≈ 330 s a teljes feldolgozás, 27B-s átnézéssel) |

**Következtetés:** a két feldolgozó szolgáltatás kiesését a rendszer **beavatkozás nélkül** kezeli: a feladat nem
vész el, legfeljebb egy perc várakozás után újraindul. Az Ollama kiesése viszont **látható hibát** ad (FAILED + ok), és
egy kattintással újrapróbálható, de magától nem indul újra. Javaslat (nem valósítottam meg): az Ollama-kapcsolati
hibánál a docproc 1–2 alkalommal próbálja újra visszalépéssel, vagy a Spring a „nem érhető el” típusú FAILED-et
később maga adja át újra.

**Diára:** „Hibatűrés: docproc leállítva → magától folytatja (117 s), hangszolgáltatás leállítva → magától folytatja
(újraátadás 30 s után, kész 370 s), Ollama leállítva → FAILED + ok, 1 kattintással újrapróbálható (57 s).”

---

## E) Tárhelyigény

```
python tools\meresek_2026-11\e_tarhely.py
```

| Mutató | Érték |
|---|---|
| Mért hangfelvételek | 8 db, összesen 352 s, WebM/Opus (böngésző MediaRecorder) |
| Hang bájt/másodperc | **16 076 B/s ≈ 128,6 kbit/s** (felvételenként 15 025–16 378) |
| Egy 5 perces felvétel | **4,82 MB** |
| PDF-lelet (27 egyedi) | átlag **115,7 kB** (medián 97,9 kB, 23–236 kB) |

**Feltevés:** 100 orvos × 10 beteg/nap × 250 munkanap = 250 000 ellátás/év; ellátásonként egy 5 perces felvétel és 2 PDF.

| | Éves igény |
|---|---|
| Hang (128 kbit/s, ahogy most tároljuk) | **1 206 GB** |
| PDF | **58 GB** |
| **Összesen** | **≈ 1,26 TB/év** (+ adatbázis, ami ehhez képest elhanyagolható) |

Megjegyzés a dia szövegéhez: a bírálatra adott válasz-vázlat „5 perces Opus kb. 1–2 MB”-ot feltételez. Ez kb.
32 kbit/s-os Opusnak felel meg. A mostani böngészős felvétel ennek kb. a négyszerese (128 kbit/s, 4,8 MB / 5 perc).
32 kbit/s-ra kódolva (beszédhez elég) az éves hangigény **≈ 300 GB** lenne.

**Bevezetve (2026-11-15, Szabi döntése):** a böngésző `audioBitsPerSecond: 32000`-rel rögzít
(`frontend/src/lib/hangfelvetel.ts`). Ez a hangprofilt (8 mondat) és a betegfelvételt is érinti, mert mindkettő
ugyanazzal a függvénnyel rögzít. Ellenőrzés: egy 57 s-os tesztfelvétel 32 kbit/s-os Opusra kódolva (ffmpeg, libopus)
végigment a teljes szerveroldali úton. Az eredeti (128 kbit/s) felvételhez képest **ugyanaz a 13 megszólalás és
ugyanazok a beszélők** jöttek ki, az ASR-szöveg pedig szóra azonos lett (96 szó, 0 eltérés).

```
python tools\meresek_2026-11\bitrata_proba.py --file proba32k.webm --ref-recording 13
```

| | 128 kbit/s (eddig) | 32 kbit/s (új) |
|---|---|---|
| Bájt / mp | 16 076 | **4 255** (≈ 34 kbit/s, mért) |
| 5 perces felvétel | 4,8 MB | **1,3 MB** |
| Éves hang (100 orvos) | 1 206 GB | **319 GB** |
| Éves összesen PDF-fel | ≈ 1,26 TB | **≈ 0,38 TB** |

A böngésző tényleges bitrátáját az első valódi felvétel után az `e_tarhely.py` újrafuttatása mutatja meg; a régi
felvételek változatlanok.

---

## F) WER-mérés előkészítése (Szabi végzi felolvasással)

Elkészült: `tools/meresek_2026-11/wer.py` és `tools/meresek_2026-11/wer_szovegek.md` (W1 echo lelet, W2 sürgősségi
anamnézis, W3 gyógyszerlista, W4 laborértékek, W5 ambuláns lap zárása; 93 / 86 / 75 / 85 / 78 szó).

A `wer.py` a felvétel megszólalásait `turn_id` sorrendben fűzi össze, és két szöveget mér: **ASR** = az AI-javítás
ELŐTTI szöveg (`utterance.asr_text`, ha a javítás átírta, különben `text`) és **javított** = `utterance.text`. Mindkettőre
kétféle WER-t ad: **nyers** (kisbetű, írásjelek nélkül) és **normalizált** (a számok mindkét oldalon szavakkal,
`num2words lang='hu'`, tizedesvesszővel is; `%` → százalék, `/` → per). A kimenetben csak szám van (felvételenként
és összesítve, helyettesítés / törlés / beszúrás bontással), a `--verbose` kiírja az illesztést (a hibás szavakat).

### Útmutató Szabinak

1. Indítsd a rendszert (Postgres, backend, docproc, voice service, Ollama, frontend), és nyisd meg a frontendet.
2. Hozz létre egy új tesztbeteget (pl. „WER Teszt”, TAJ nélkül), válaszd ki, és nyiss neki ellátást.
3. A felvételnél válaszd az **echo** módot (a diktálásnál ez a helyes: egybeszélős, csak az orvos).
4. Vedd fel egymás után az 5 szöveget, **W1, W2, W3, W4, W5 sorrendben, szövegenként külön felvétellel**. Olvasd
   természetes tempóban, a számokat úgy, ahogy orvosként mondanád („hétegész-nyolc”, „százötvennyolc per
   kilencvennégy”). Ha elrontasz egyet, töröld (vagy jegyezd fel az id-jét), és vedd fel újra.
5. Várd meg, amíg mind az 5 felvétel **kész (DONE)** lesz (a 27B-s átnézés miatt felvételenként 1–3 perc).
6. Nézd meg a beteg id-jét (az URL-ben vagy: `docker exec mediai-assistent-db-1 psql -U postgres -d mydatabase -c "select id from pacient order by id desc limit 1"`).
7. Egyszer telepítsd a számátírót:
   ```
   pip install num2words
   ```
8. Futtasd (ha pontosan 5 kész felvétel van, sorrendben W1–W5-höz rendeli):
   ```
   python tools\meresek_2026-11\wer.py --patient <beteg-id>
   ```
   Ha volt elrontott vagy ismételt felvétel, add meg kézzel a párokat (felvétel-id = szöveg):
   ```
   python tools\meresek_2026-11\wer.py --map 41=W1 42=W2 44=W3 45=W4 46=W5
   ```
   A hibák szavanként: `--verbose`. Az eredmény a táblázat, és `tools/meresek_2026-11/eredmenyek/wer_*.json`.
9. A diára: a normalizált WER (ASR és javított) összesítve, mellette a nyers. A kettő különbsége mutatja, mennyi
   hiba csak számírás-formátum. Az ASR → javított különbség mutatja, mennyit javít az átnézés. Mintaméret: 5 szöveg,
   ~417 szó, 1 beszélő (a saját hangod) — ez a korlátja.

DER-mérés nincs előkészítve (a feladat a WER volt). Az echo mód egybeszélős, ezért a W1–W5 felvételekből DER nem mérhető.

### Eredmény (2026-11-15, Szabi két felolvasása)

**Minta:**
- echo mód, egy beszélő (Szabi), W1–W5 egy-egy felvétellel (58–81 s), szövegenként 419 referencia-szó;
- **két független felolvasás:** 1. a 22-es beteg (18, 20–23-as felvétel), 2. a 25-ös beteg (29–33-as felvétel);
- **bitráta:** a 2. felolvasás minden felvétele és az 1. felolvasás W2–W5 felvétele 32 kbit/s-os frontenddel készült
  (mért ≈ 3 250–3 340 B/s ≈ 26 kbit/s, Opus VBR), az 1. felolvasás W1-e még 128 kbit/s-mal;
- **referenciajavítás:** a W3 két elírását javítottam („50 mgreggel” → „50 mg reggel”, „Rampril” → „Ramipril”; az
  eredeti szövegben „Ramipril” állt, és a 2. felolvasás ASR-je is „ramipril”-t írt). A számok ezzel készültek.

```
python tools\meresek_2026-11\wer.py --map 24=W1 25=W2 26=W3 27=W4 28=W5    # 1. felolvasás, működő átnézéssel újrafuttatva
python tools\meresek_2026-11\wer.py --patient 25                            # 2. felolvasás
```

| Szöveg | WER nyers (1. / 2.) | WER normalizált (1. / 2.) | Írásmód-független (1. / 2.) | CER (1. / 2.) |
|---|---|---|---|---|
| W1 echo lelet (93 szó) | 14,0% / 23,7% | 14,0% / 23,7% | 8,6% / 18,3% | 1,3% / 2,1% |
| W2 sürgősségi anamnézis (88) | 15,9% / 21,6% | 15,7% / 19,1% | 11,2% / 15,7% | 1,5% / 1,3% |
| W3 gyógyszerlista (75) | 14,7% / 17,3% | 14,7% / 17,3% | 13,3% / 16,0% | 3,2% / 6,0% |
| W4 laborértékek (85) | 18,8% / 24,7% | 15,7% / 19,6% | 7,8% / 11,8% | 1,1% / 4,7% |
| W5 ambuláns lap zárása (78) | 38,5% / 29,5% | 31,1% / 24,4% | 28,9% / 16,7% | 5,7% / 2,3% |
| **Összesen (419 szó)** | **20,0% / 23,4%** | **18,3% / 20,9%** | **13,8% / 15,6%** | **2,5% / 3,1%** |
| **A két felolvasás együtt (838 szó)** | **21,7%** | **19,6%** | **14,7%** | **≈ 2,8%** |

- *Normalizált:* a számok mindkét oldalon szavakkal (num2words, hu).
- *Írásmód-független:* a latinos / magyaros írásmód (hypertonia ↔ hipertónia, c ↔ k, sz ↔ s, ph ↔ f, ae ↔ e), az
  ékezet és a kettőzött betű nem számít.
- *CER:* karakterhiba-arány ugyanezen, szóköz nélkül (az egybe- / különírás, pl. „bal kamra” ↔ „balkamra” sem számít).

**Szóródás:** ugyanaz a felolvasó és ugyanaz a szöveg két felvételen 18,3% és 20,9% normalizált WER-t adott. A
szövegenkénti különbség 4–10 százalékpont, és nem egyirányú (W5 az 1., a többi a 2. felolvasásban rosszabb). Az
egyes szövegek WER-jét ezért csak tartományként érdemes közölni; az 5 szöveg összesítése stabilabb.

A hibák három csoportba esnek:
1. **Írásmód** (helyes szó, más helyesírással): hypokinetikus ↔ hipokinetikus, coronarographia ↔ koronarografia,
   millimól ↔ millimol. Ez adja a normalizált és az írásmód-független WER közti 4–5 százalékpontot.
2. **Egybe- / különírás:** bal kamra ↔ balkamra, inferior elvezetésekben ↔ inferiorelvezetésekben, szorító jellegű ↔
   szorítójellegű. A WER-ben 2 hiba, a CER-ben 0.
3. **Valódi félreismerés:** mindkét felolvasásban a szakszavak, gyógyszernevek, mértékegységek és a BNO-kódok.
   - 1. felolvasás: regurgitáció → regularizáció, **mikromól → millimol**, fehérje → jön, sacubitril → sfatupitril.
   - 2. felolvasás: stenosis → senozis, **milliliter → milliméter**, **három → kilenc** (időpont), vesefunkció →
     versenyfunkció, furosemid → „furos semely”, a BNO-kódok („BNOI50.9”).

   A mértékegység- és számhibák klinikailag a legveszélyesebbek.

**Az AI-átnézés gyakorlatilag nem változtatott:**

| Felolvasás | Átnéző modell | Kérdés | Javítás | Érték-jelölés | Hatás a WER-re |
|---|---|---|---|---|---|
| 1. (a felvételkori feldolgozás) | nem futott le | — | — | — | — |
| 1. újrafuttatva | 27B (W1–W4), 4B (W5) | 41 (W1–W4) | 0 | 0 | 0 |
| 2. | 4B (`medgemma:latest`) | — | 1 | 3 | 0 |

- **Az 1. felolvasás felvételkori feldolgozása:** W1-nél a 27B túllépte a `.env` 120 s-os `LLM_DEADLINE`-ját (egy
  echo-átnézés ezen a GPU-n 100–280 s); W2–W5-nél a hangszolgáltatás nem létező modellnevet (`medgemma:4b`) kapott.
- **Az újrafuttatás:** ugyanazok a hangfájlok új felvételként, `LLM_DEADLINE=900`-zal (24–28-as felvétel, „WER Teszt
  újrafuttatás” beteg). Az átnézés lefutott, de nem változtatott semmit.
- **A 2. felolvasás:** az egyetlen javítás („creatinín” → „creatinin”) írásmód-változás. A 3 érték-jelölés **téves**:
  a W5 BNO-kódjait (I50.9, E11.9, I10) mérési értéknek nézte.

Értelmezés:
- Az átnézés csak a Whisper szerint bizonytalan szavakról kérdez (valószínűség < 0,5), a valódi félreismeréseket a
  Whisper magabiztosan követte el.
- Az átnézés tehát nem rontott, de ezeken a hibákon nem segít; echo-diktálásnál a BNO-kódot tévesen értéknek veheti.
- **Az ASR determinisztikus:** ugyanaz a hangfájl két feldolgozásban szóra azonos átiratot adott (5/5).

**Következmények / javaslat (nem valósítottam meg):**
- A `.env`-ben a hangátnézéshez vagy `VOICE_REVIEW_MODEL=medgemma:latest`, vagy a 27B-hez `LLM_DEADLINE` ≥ 600 kell.
  A mostani beállítással a 27B-s átnézés ezen a gépen csendben kimarad: csak egy figyelmeztetés jelzi.
- A szakszó-, mértékegység- és gyógyszernév-hibákra a Whisper szókincs-támogatása (faster-whisper `initial_prompt` /
  `hotwords` a PUPHA-nevekből és gyakori leletkifejezésekből) vagy a Whisper finomhangolása a célzott út, nem az
  LLM-átnézés.
- Az érték-kérdésből a BNO-kódot (`[A-Z]\d{2}(\.\d+)?`) ki kellene zárni.

**Diára (8. dia) javasolt szám:** „WER 18–21% (normalizált; 2 felolvasás × 5 szöveg, 838 szó, 1 beszélő) ·
írásmód-függetlenül 14–16% · CER 2,5–3,1% · a hibák fele írásmód, a valódi hibák szakszavak, mértékegységek,
BNO-kódok”. A tartományt javaslom, nem az átlagot (19,6%): két felolvasásból az átlag pontosabbnak tűnne, mint
amilyen; a tartomány a szóródást is mutatja.

---

## G) Az orvos terhe (ellenőrzés, jóváhagyás)

```
python tools\meresek_2026-11\g_orvos_terhe.py
```

A DB állapota a 6 dokumentum újrafeldolgozása után: 18 beteg, ebből 16-nak van állítása; **1077 állítás**.

| Típus | Db | Betegenként átlag / medián (min–max) |
|---|---|---|
| DETERMINISTIC_RULE (szabály) | 154 (lab-reference-range 144, drug-interaction 10) | 9,6 / 10 (0–15) |
| MODEL_INFERENCE (modell) | 922 | 57,6 / 6 (0–162) |
| RETRIEVED_FACT (forrás) | 1 | 0,06 / 0 (0–1) |
| **Összesen** | **1077** | **67,3 / 18,5 (1–173)** |

| Súlyosság | Db | Betegenként átlag / medián (min–max) |
|---|---|---|
| CRITICAL | 4 | 0,25 / 0 (0–1) |
| WARNING | 157 | 9,8 / 10 (0–16) |
| INFO | 916 | 57,3 / 6 (0–161) |

| Döntés (legfrissebb) | Db |
|---|---|
| **PENDING (döntésre vár)** | **1056 (98,1%)** — betegenként átlag 66,0, medián 14 (0–173) |
| ACCEPTED | 13 |
| MODIFIED | 4 |
| REJECTED | 4 |

A szabály-állítások mind (154/154) PENDING-ek, a gyógyszerek mind (55/55) jóváhagyatlan pipeline-forrásból vannak,
a felvétel-kulcsszavakból 47 javaslat döntés nélkül maradt (4 elfogadva, 2 orvos adta hozzá), a csoportokban 28
AI-elem él (4 orvos által szerkesztve) és 5 orvosi elem. A MODEL_INFERENCE állítások 99%-a (912/922) INFO szintű
dokumentumkivonat (státusz, gyógyszer, panasz, vizsgálat, diagnózis …), és 909/922 a 22–23 leletes tesztbetegeknél van.
**Értelmezés:** a rendszer a döntés nélküli állítások mellett is teljes értékűen működik. Ez éppen azt mutatja,
hogy a jóváhagyás nem előfeltétel.

### Kötelező-e bárhol a jóváhagyás? — a kód alapján

**Csak egy helyen kötelező:** ha az orvos **kézzel** vesz fel gyógyszert vagy allergiát egy állításból forrásként.
- `backend/src/main/java/bme/mediai/statement/service/StatementApprovalService.java:68` — `requireApprovedStatementOfPatient`: csak ACCEPTED / MODIFIED állítás lehet forrás (különben 409).
- Hívói: `medication/service/MedicationService.java:51`, `allergy/service/AllergyService.java:41`.
- Frontend: `frontend/src/components/GyogyszerAllergiaPanel.tsx:14`, `:36` — forrásként csak jóváhagyott állítást kínál.

**Megerősítve — a gyógyszerlista PENDING állításból is feltöltődik („nem ellenőrzött” jelöléssel):**
- `backend/src/main/java/bme/mediai/processing/service/DocumentProcessingService.java:158` — a lelet kivonatai MODEL_INFERENCE állításként, döntés nélkül jönnek létre, `:166` / `:171` — és ezekből azonnal gyógyszer / allergia lesz.
- `medication/service/MedicationService.java:70–107` (`createFromPipeline`) — `EntryOrigin.DOCUMENT_PIPELINE`, `verified = false`; `:172` — a `verified` a forrás-állítás legfrissebb döntéséből számolódik (`approvedAmong`).
- `frontend/src/components/GyogyszerAllergiaPanel.tsx:73–77` — „nem ellenőrzött · AI-kinyerés” jelölés, de a tétel a listán van.

**Megerősítve — a laboroldal jóváhagyás nélkül mutatja az adatot:**
- `DocumentProcessingService.java:134` — a laborsorok a `lab_result` táblába közvetlenül kerülnek. Ezek nem állítások, így nincs mit jóváhagyni; a Labor oldal (`LabTrend.tsx`) ezeket mutatja.
- A tartományon kívüli érték riasztása (`rule/service/LabReferenceRangeRule.java`) szabály-állítás, döntés nélkül is megjelenik.

**Megerősítve — a Klinikai státusz jóváhagyás nélkül is dolgozik:**
- `backend/src/main/java/bme/mediai/summary/service/SummaryFactsBuilder.java:78–80` — minden laborsor; `:84–86` — minden állítás, **csak a REJECTED kiszűrve** (a PENDING bemegy, a `reviewStatus`-szal együtt); `:100–102` — az interakciós riasztások szintén (REJECTED és INFO nélkül).
- `frontend/src/components/KlinikaiStatusz.tsx:172`, `:198` — az interakciónál csak az elutasítottat szürkíti, az elfogadottat „orvos elfogadta” jelzi; a PENDING is látszik.

**Diára:** „1077 állítás 16 betegnél (medián 18,5 / beteg), 98,1% döntés nélkül — és a rendszer így is használható:
a jóváhagyás csak a kézi gyógyszer/allergia-felvételhez kötelező. Az orvos a Klinikai státuszból, a gyógyszer- és a
laboroldalról tájékozódik; az állítás-kártya a kontrollt adja (elfogad / elutasít / szerkeszt), nem kötelező
munkalépés.” Fenntartás: a betegek tesztbetegek (ugyanazok a leletek ismételten), a valós orvosi időterhet ez nem méri.

---

## Hogyan kerül be az előadásba — javasolt diák

1. **Valós leletek (A):** egy számsor + kis táblázat: 27 lelet / 176 sikeres feldolgozás / 1831 sor / 99,0% szó szerint
   visszakereshető / 64% PDF-kiemelés / 144 riasztás. Lábjegyzet: saját leletek, a kézi ellenőrzés (30 sor) eredménye.
2. **Mérés dia — két újdonság (B):** 2×2-es mátrix (szó szerinti / átfogalmazott × elfogadva / elutasítva: 30/0 – 0/30)
   + „5/5 szabálynak álcázott kérés elutasítva” + „visszavonás: labor 10/10, gyógyszer 10/10”.
3. **Ismételhetőség (C):** táblázat: modell × seed × futás → helyes javítások (átlag, terjedelem) és egyezési arány 100%.
   Egy mondat: a `VOICE_REVIEW_SEED` bevezetve, de `temperature: 0` mellett a seed nélküli futás is azonos volt a 10-27-ivel.
4. **Hibatűrés (D):** 3 soros táblázat (beavatkozás → viselkedés → helyreállási idő).
5. **WER (F):** Szabi mérése után: normalizált WER (ASR → javított), n = 5 szöveg, ~417 szó.
6. **Skálázás (E):** egy sor a GPU-számítás mellé: „hang 32 kbit/s Opus: 1,3 MB / 5 perc → 100 orvos ≈ 0,38 TB/év (a korábbi 128 kbit/s-mal 1,26 TB lett volna)”.
7. **Orvos terhe (G):** egy diagram (PENDING vs. döntött) + a „jóváhagyás nem előfeltétel” állítás fájl:sor-alapú igazolása.

## Ami nem futott le / nyitott

- **Kézi ellenőrzés:** a `kezi_ellenorzes.csv` kitöltése Szabié.
- Ebben a szálban csak a méréseket végeztem; az iparjogvédelmi táblázat, a „MediAI” név foglaltságának ellenőrzése, a
  TAM/SAM-indoklás és a QR-kódok nem itt készültek.
