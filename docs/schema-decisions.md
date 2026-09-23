# Adatmodell-döntések

A teljes ER-diagram a repóban: `docs/er-diagram.png` (töltsd be ide a
legutóbbi drawio-exportot). Ez a fájl a döntések *miértjét* rögzíti, amit
egy diagram nem mutat.

## Táblalista (célállapot — nem mind létezik még migrációban)

`felhasznalo`, `hangprofil`, `paciens`, `ellatas`, `hozzajarulas`,
`dokumentum`, `labor`, `felvetel`, `megszolalas`, `allitas`,
`allitas_jovahagyas`, `hozzaferes_naplo`, később: `gyogyszer`, `allergia`,
`doc_chunk`, `iranyelv_chunk`.

Jelenleg migrálva: `pacient`, `app_user` (= a diagram `felhasznalo`-ja),
`voice_profile` (= `hangprofil`), `recording` (= `felvetel`),
`utterance` (= `megszolalas`), `medical_care` (= `ellatas`),
`statement` (= `allitas`), `statement_approval` (= `allitas_jovahagyas`),
`document` (= `dokumentum`), `document_chunk` (= `doc_chunk`),
`lab_result` (= `labor`), `medication` (= `gyogyszer`), `allergy` (= `allergia`).

## `app_user` (felhasznalo)

Angol elnevezés, a tábla neve `app_user`, mert a `user` Postgresben
foglalt szó. Jelszó sosem tárolódik nyersen: `password_hash` (BCrypt, a
`spring-security-crypto` modulból — a teljes Spring Security még nincs
bekötve). A szerepkör `DOCTOR | ADMIN`, CHECK-kel kikényszerítve.

## `voice_profile` (hangprofil)

Felhasználónként több sor (a Python `--uj` a régit `.bak`-ként megtartja),
de legfeljebb egy `active = true` — részleges egyedi indexszel
(`WHERE active = true`) kikényszerítve, nem csak kódban. Létrehozáskor a
service a régi aktívat inaktiválja és **flush-ol** a beszúrás előtt (IDENTITY
miatt az INSERT azonnal fut, a dirty-check UPDATE csak commitkor).

Az `embedding vector(256)` (wespeaker-resnet34, float32) oszlop létezik, de
a JPA-entitás szándékosan NEM képezi le: egyelőre a Python hangmodul
kezeli a vektort, a Spring csak metaadatot. Ha Java-oldalon kell, a
`hibernate-vector` + `float[]` + `@JdbcTypeCode(SqlTypes.VECTOR)` az út.
`threshold` nullázható — a Python tesztenként adja meg, nem a profilban.
`ON DELETE CASCADE` az `app_user`-re: a hanglenyomat biometrikus adat,
a fiókkal együtt törlendő.

## `recording` (felvetel)

- **Törlési szabályok**: `patient_id` → `ON DELETE CASCADE` (beteg-gyerektábla);
  `user_id` → `RESTRICT` (orvos nem törölhető, amíg felvételei vannak — a
  betegdokumentáció nem tűnhet el vele); `voice_profile_id` → `SET NULL`
  (a biometrikus profil törölhető, a felvétel megmarad).
- **`medical_care_id`** nullázható; V5-ben `encounter_id` néven, FK nélkül jött
  létre — a V8 nevezte át és tette rá az FK-t (ld. `medical_care`).
- **`voice_profile_id` nem a klienstől jön**: létrehozáskor a service az
  orvos akkor aktív profilját írja be (vagy NULL).
- **Státusz-állapotgép** (`RecordingStatus.canTransitionTo`, az entitás
  kényszeríti ki): `RECORDING → PROCESSING | FAILED`,
  `PROCESSING → DONE | FAILED`, `DONE`/`FAILED` végállapot. Tiltott
  átmenet → 409. `duration_sec` a `PROCESSING`-re lépéskor kötelező.
- **`recorded_at` = a felvétel létrejöttének pillanata**, a DB adja
  (`default now()`, V6), a kliens nem küldheti; nincs külön `created_at`.
  Entitáson `@Generated` + `insertable=false` — Java-oldalon NEM állítjuk
  (a kettő keverése miatt a V5-ös sorok NULL-t kaptak).
- `asr_model` NOT NULL: a voice-module.md szerint az `eloallito`-ban az
  ASR-modellt is rögzíteni kell, ez a forrása.

## `medical_care` (ellatas)

- **Identitás-kapu DB-szinten** (architecture.md: eltérés = hard fail):
  összetett FK `recording(medical_care_id, patient_id) → medical_care(id,
  patient_id)`, ehhez `UNIQUE (id, patient_id)` a medical_care-en. Így egy
  felvétel betege csak az ellátás betege lehet — közvetlen SQL-ből (pl. a
  Pythonból) sem kerülhető meg. A `RecordingService` előre ellenőriz, hogy
  500 helyett 409 legyen belőle.
- **Életciklus**: `started_at` DB `now()`; zárás `POST /{id}/close`, ami
  natív `UPDATE ... SET ended_at = now() WHERE ended_at IS NULL` — atomikus,
  DB-idő, csak egyszer sikerül (második → 409). Lezárt ellátáshoz új
  felvétel nem rendelhető (409). Az entitáson `ended_at` is
  `insertable/updatable = false`: csak ez a lekérdezés írja.
- **Több nyitott ellátás is lehet** betegenként (pl. SBO alatt párhuzamos
  echo) — tudatosan nincs részleges egyedi index; a felvételnél ezért mindig
  explicit `medicalCareId` kell.
- **Törlés**: `patient_id` CASCADE, `user_id` RESTRICT. A felvétel → ellátás
  FK **`NO ACTION`, nem `RESTRICT`**: közvetlen törlést ugyanúgy tilt, de a
  beteg törlésekor (CASCADE mindkét táblára) a `RESTRICT` az utasítás
  közepén, a még ki nem törölt felvételen elhasalna; a `NO ACTION` csak az
  utasítás végén ellenőriz.
- `type`: `SBO | AMBULANCE | ECHO` (diagram: `sbo | ambulans | echo`).

## `utterance` (megszolalas)

- **Megváltoztathatatlan.** Az `allitas.forras` `(recording_id, turn_id)`
  párral hivatkozik rá (`UNIQUE`), egy utólagos módosítás a provenance-t
  hamisítaná — ezért nincs update/delete végpont és módosító metódus.
- **Egyben, egyszer töltődik be**: `POST /api/recordings/{id}/utterances`
  a teljes listával, egy tranzakcióban, csak `PROCESSING` felvételhez;
  második próbálkozás → 409. (voice-module.md határ: a Spring a kész
  eredményt kapja, nem élő átiratot.)
- `speaker`: `DOCTOR | PATIENT | OTHER` — a Python `orvos | beteg | egyéb`
  kimenetének 1:1 megfelelője. Az `OTHER` a hozzátartozó / harmadik fél.
  **Tudatos döntés: nincs `UNCERTAIN`** — bizonytalan hozzárendelés most
  nem jelölhető.
- `start_ms`/`end_ms` egész ms (a Python másodpercet ad → átváltás az
  integrációnál). `turn_id` 0-tól indul.
- `similarity`, `asr_confidence` nullázható: a Python jelenleg egyiket sem
  adja ki; a hasonlóság ott beszélőnként (klaszterenként) számolódik, nem
  megszólalásonként.
- `ON DELETE CASCADE` a `recording`-ra (beteg → felvétel → megszólalás).

## Miért JSONB az `allitas.forras` és `eloallito`

Három különböző forrástípust kell hordoznia egy oszlopban: szöveg
char-offset, kép bbox, hang `felvetel_id`+`turn_id`. Normalizált táblával
ez három nullolható oszlopcsoport lenne. Ára: nincs FK-kényszer rajta, a
`turn_id` érvényességét kódban kell ellenőrizni.

## Miért nincs FK a `hozzaferes_naplo.paciens_id`-n

Szándékos. A beteg törlése után is meg kell maradnia az audit-nyomnak,
hogy ki nézte meg korábban — egy `ON DELETE CASCADE` pont a bizonyítékot
törölné.

## `statement` / `statement_approval` viszonya (allitas / allitas_jovahagyas) — V10

1:N, nem 1:1 — megőrzi a jóváhagyási előzményt, ha az orvos meggondolja
magát. **Konvenció**: mindig a legfrissebb döntés az érvényes állapot,
rendezés `decided_at DESC, id DESC` (az id dönt azonos időpontnál). Ez a
szabály **egyetlen helyen** él: a `StatementApprovalRepository`
lekérdezéseiben — a kliens nem számolja, a `StatementResponse`
`reviewStatus` (`PENDING`, ha nincs döntés) és `effectiveText` mezőt kap.

- `status`: `ACCEPTED | REJECTED | MODIFIED`. `modified_text` akkor és csak
  akkor, ha `MODIFIED` (DB CHECK + entitás). Az eredeti állítás szövege
  sosem változik — a modell kimenete és az orvos javítása is visszakereshető.
- **Csak `DOCTOR` hagyhat jóvá** (409). Bejelentkezés még nincs, a `userId`
  a kérésből jön — autentikáció után a bejelentkezett felhasználóból kell.
- **Szabály-állítás nem `MODIFIED`-olható** (409), csak elfogadható /
  elutasítható: a szabály kimenete reprodukálható tény. Ezt csak a service
  kényszeríti ki (DB-ben tábla-közi CHECK trigger kellene).
- Törlés: `statement_id` CASCADE, `user_id` RESTRICT.
- A jóváhagyás a `statement` modulon belül van (nem külön modul): a
  `StatementResponse` az állapotot, a jóváhagyás a szabály-típust igényli —
  két modul kölcsönösen függene egymástól.

## `statement` (allitas) — V9

Típusok: `DETERMINISTIC_RULE | RETRIEVED_FACT | MODEL_INFERENCE`
(= `szabaly | forras | modell`). Megváltoztathatatlan; a jóváhagyás külön
tábla lesz (`allitas_jovahagyas`).

**Invariánsok — mind DB CHECK-kel ÉS a `Statement` konstruktorában:**

```sql
CONSTRAINT rule_has_no_uncertainty CHECK (type <> 'DETERMINISTIC_RULE' OR uncertainty IS NULL);
CONSTRAINT model_needs_uncertainty CHECK (type <> 'MODEL_INFERENCE'    OR uncertainty IS NOT NULL);
-- + producer_matches_type: RULE ↔ DETERMINISTIC_RULE, MODEL ↔ MODEL_INFERENCE,
--   EXTRACTOR ↔ RETRIEVED_FACT;  model_producer_complete: modelName + seed + promptHash
```

- **Az API nem hozhat létre `DETERMINISTIC_RULE`-t** (400) — különben egy
  LLM-kimenet kliensoldalról szabálynak álcázhatná magát. Csak a (majdani)
  Java szabálymotorok hozhatják létre. Jelenleg ilyen nincs.
- **`uncertainty`**: `LOW | MEDIUM | HIGH` — kategória, nem szám: a kis
  LLM-ek numerikus valószínűségei rosszul kalibráltak.
- **`source` (MERRE), JSONB, `type` diszkriminátorral**: `AUDIO {recordingId,
  turnIds[]}`, `DOCUMENT {documentId, charStart, charEnd}`, `IMAGE
  {documentId, page, x, y, width, height}` (bbox normált 0–1). AUDIO-nál a
  service ellenőrzi: a felvétel létezik, **ugyanannak a betegnek** a felvétele
  (409), és a turn_id-k léteznek. DOCUMENT/IMAGE-nél (V11 óta): a dokumentum
  létezik (404), **ugyanannak a betegnek** a dokumentuma (409); DOCUMENT-nél
  van kinyert szövege (409) és a tartomány belefér (400); IMAGE csak kép/PDF
  dokumentumra mutathat (400).
- **`RETRIEVED_FACT` = szó szerint** (kisbetűs, whitespace-normalizált
  összevetés): AUDIO-nál a szöveg benne van a hivatkozott megszólalásokban;
  DOCUMENT-nél **egyezik** a `[charStart, charEnd)` szövegrésszel. Különben
  400 — átfogalmazott szöveg `MODEL_INFERENCE`. IMAGE-nél nem ellenőrizhető
  (a bbox-hoz nincs OCR-szöveg) — ismert rés.
- **`producer` (HOGY), JSONB**: `RULE {ruleId, ruleVersion}`, `MODEL
  {modelName, seed, promptHash, asrModel}`, `EXTRACTOR {extractorName,
  extractorVersion, asrModel}`. Az `asrModel`-t AUDIO forrásnál a service a
  felvételből írja be (voice-module.md: mindkét modell rögzítendő), a
  kliensét felülírja.
- **MIKOR**: `created_at` DB `now()`; `clinical_time` NOT NULL — ha a kliens
  nem küldi, AUDIO forrásnál a felvétel `recorded_at`-je, DOCUMENT/IMAGE-nél
  a dokumentum `clinical_date`-je budapesti éjfélkor; ha az sincs, kötelező
  (400). Tudatos kompromisszum: pl. „két napja fáj” tünetnél a
  felvétel ideje nem a tünet kezdete — ha fontos, a kinyerő küldje.
- JSONB ↔ Java: `@JdbcTypeCode(SqlTypes.JSON)` sealed interface + record-ok,
  Hibernate 7.4 Jackson 3 FormatMapperrel.

## `lab_result` (labor) — V13

`value_num NUMERIC(12,4) NOT NULL` a motoroknak (összehasonlítás referencia-
tartománnyal). Cenzorált (`<0,5`) vagy kvalitatív (`negatív`) eredményt
NEM kerekítünk bele csendben — **az import elutasítja** (nincs
`ertek_szoveg` oszlop). FHIR `Quantity` (value+comparator+unit) a "helyes"
hosszú távú megoldás, de most nem építjük meg — dokumentált tudatos
egyszerűsítés.

- **Az érték JSON számként jön** (a kliens értelmezi a leletet). Ezért a
  `<0,5`/`negatív` szöveg JSON-parse hibán bukik el (400). Mellékhatás: a
  számot tartalmazó szöveget (`"139"`) a Jackson elfogadja.
- **Csendes kerekítés ellen**: `@Digits(integer = 8, fraction = 4)` a
  kérésben — a `NUMERIC(12,4)` a több tizedesjegyet szó nélkül kerekítené,
  ezt 400-zal elutasítjuk.
- **Egy panel = egy kérés, egy tranzakció** (`POST /api/lab-results`): ha
  egy sor hibás, egyik sem kerül be. Megváltoztathatatlan.
- **`document_id` nullázható, DB-identitáskapuval**: összetett FK
  `(document_id, patient_id) → document(id, patient_id)` (ehhez a V13
  `UNIQUE (id, patient_id)`-t tett a `document`-re). `NO ACTION`: a
  dokumentum nem törölhető, amíg laborsor hivatkozik rá; a beteg törlése
  mindent visz (CASCADE).
- `ref_low`/`ref_high` külön-külön nullázható (egyoldalú tartomány), CHECK
  `ref_low <= ref_high`. `unit` nullázható (pl. pH, INR).
- `measured_at`: ha hiányzik, a dokumentum `clinical_date`-je budapesti
  éjfélkor; ha az sincs, 400.
- Trend: `GET /api/lab-results?patientId=&name=` időrendben, index
  `(patient_id, name, measured_at)`. **Nincs egységkonverzió** — azonos
  `name` más `unit`-tal a trendben keveredhet.
- **Következő lépés**: a referencia-tartomány szabály (első determinisztikus
  motor) → `DETERMINISTIC_RULE` állítások; ehhez `LAB` forrástípus kell a
  `statement.source`-ba.

## `document` (dokumentum) — V11

- **A Spring fogadja a fájlt** (multipart), és **maga számolja** az SHA-256-ot,
  a méretet és a MIME-típust (magic bytes: PDF/PNG/JPEG, különben érvényes
  UTF-8 → `text/plain`, más → 400). A kliens `Content-Type`-ja nem számít.
- **Tárolás**: `mediai.storage.documents-dir` alatt
  `{patientId}/{sha256}.{ext}`; az adatbázisban csak a relatív út. A `data/`
  **gitignore-olva** — betegdokumentum sosem kerülhet a gitbe.
- **Duplikátum**: `UNIQUE (patient_id, sha256)` → 409. Más betegnél ugyanaz a
  fájl feltölthető (külön példányban tárolva).
- **`extracted_text` egyszer állítható** (`PUT /{id}/text`, NULL → szöveg,
  atomikus natív UPDATE; másodszor 409). Oka: a `statement.source`
  karakterpozícióval hivatkozik rá — utólagos módosítás csendben rossz
  szövegrészre mutatna. CLAUDE.md: char-offsettel csak kinyert szöveggel
  rendelkező dokumentumra lehet hivatkozni (`StatementService`, 409).
- **Offszetek Unicode code pointban** (mint Pythonban), nem UTF-16 egységben.
- `type`: `LAB | AMBULANCE | IMAGING` (diagram: `labor | ambulans | kepalkoto`).
- **Ismert rés — GDPR**: a beteg törlésekor a `document` sorok CASCADE-del
  törlődnek, de a **fájlok a lemezen maradnak**. Törlési folyamatnál ezt
  külön kell kezelni.
- A code-point alapú kivágás egyetlen helyen van: `Document.textSpan()` —
  az állítás-forrás és a chunk is ezt használja.

## `document_chunk` (doc_chunk) — V12

RAG-darabok a `document.extracted_text`-ből, a diagram szerint.

- **A Python darabol** (ott van a BGE-M3 tokenizáló) és csak
  `charStart/charEnd`-et + embeddinget küld; a **`text`-et a Spring vágja
  ki** a kinyert szövegből (`Document.textSpan`). Így a chunk szövege és
  pozíciója sosem mondhat ellent egymásnak — egyetlen forrásuk van.
- **Egyben, egyszer** (`POST /api/documents/{id}/chunks`, második → 409),
  csak kinyert szöveggel rendelkező dokumentumhoz (409). Átfedés megengedett
  (csúszóablak), azonos tartomány nem (`UNIQUE`, 400).
- **`embedding vector(1024) NOT NULL`**, a Spring API-n jön, `float[]` +
  `@JdbcTypeCode(SqlTypes.VECTOR)` (`hibernate-vector`). Dimenzió és
  NaN/Infinity ellenőrizve (400). HNSW index `vector_cosine_ops`-szal.
- **`embedding_model` — a diagramon nincs, tudatos kiegészítés**: különböző
  modellek vektorai nem összehasonlíthatók; modellváltásnál e nélkül a régi és
  új vektorok csendben keverednének ugyanabban az indexben.
- `ON DELETE CASCADE` a `document`-re.

## `medication` (gyogyszer) — V14

- Mezők a diagram szerint: `name`, `active_ingredient` (hatóanyag — a
  majdani DDInter/RxNorm interakció-motor erre épít), `dose NUMERIC(10,3)`,
  `dose_unit` (= `dozis_tipus`), `route` (= `alkalmazasi_modszer`),
  `source_statement_id` (= `forras_allitas_id`), `active` (= `aktualis`).
- **`source_statement_id` nullázható** (kézi rögzítés), DB-identitáskapuval:
  összetett FK `(source_statement_id, patient_id) → statement(id,
  patient_id)` (ehhez a V14 `UNIQUE (id, patient_id)`-t tett a
  `statement`-re), `NO ACTION`.
- **Csak jóváhagyott állításból** (legfrissebb döntés `ACCEPTED` vagy
  `MODIFIED`, különben 409): a gyógyszerlista orvosi tényként jelenik meg,
  PENDING LLM-kinyerés nem kerülhet bele. **Ismert rés**: ha az állítást
  később elutasítják, a belőle már létrehozott gyógyszer aktív marad.
- **`active` egyirányú** (`POST /{id}/discontinue`, másodszor 409):
  újrakezdés = új sor, az előzmény megmarad. Nincs kezdő/leállítási dátum
  (a diagramon sincs).
- `dose` > 0, és dózishoz kötelező a `dose_unit` (DB CHECK + entitás);
  `@Digits(7, 3)` a csendes kerekítés ellen.

## `allergy` (allergia) — V15

- Mezők a diagram szerint: `substance` (= `anyag`), `source_statement_id`
  (= `forras_allitas_id`).
- Forrás-állítás: ugyanaz a szabály, mint a `medication`-nél (nullázható,
  összetett FK, csak `ACCEPTED`/`MODIFIED`). A szabály **egyetlen helyen**
  él: `StatementApprovalService.requireApprovedStatementOfPatient()`, ezt
  hívja a gyógyszer és az allergia is.
- **Egy betegnél egy anyag egyszer**: egyedi index
  `(patient_id, lower(btrim(substance)))` → a `Latex` és a ` LATEX ` is
  duplikátum (409). A szélső szóközöket a Java is levágja mentés előtt.
- Megváltoztathatatlan, nincs törlés/„aktív” flag (a diagramon sincs).
- **Nincs még gyógyszer–allergia ellenőrzés**: penicillin-allergiás betegnek
  Amoxicillin rögzíthető. Ez determinisztikus szabály-motor feladata (a
  `substance` ↔ `medication.active_ingredient` összevetés, hatóanyag-
  osztályokkal), nem LLM-é.

## `paciens.taj` formátum

`VARCHAR(9)`, `UNIQUE`, `CHECK (taj ~ '^[0-9]{9}$')`. Sose `INT` — vezető
nulla lehet a TAJ-ban, és van CDV ellenőrzőösszege (azt Java-oldalon
validáljuk, nem SQL CHECK-kel).

**Nullázható** (V2 migráció óta). Az SBO-ban lehet azonosítatlan vagy
külföldi beteg, akinek nincs vagy nem ismert a TAJ-a. A `UNIQUE` ettől még
működik: Postgresben több `NULL` nem ütközik egymással.

## Beteg-azonosítás összetett kulccsal

`findByNameAndBirthDateAndMotherName` — `List`, NEM `Optional`. Két
egyező demográfiai adatú beteg lehetséges, az adatbázis ezt nem zárja ki
(és nem is szabad, mert épp ezért létezik külön a TAJ).
