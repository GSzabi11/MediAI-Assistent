# Hangfelismerő modul — integrációs jegyzet

A teljes fejlesztői átadó dokumentum (hardver, telepítés, ASR/LLM
mérések, nyitott feladatok) itt legyen elhelyezve változtatás nélkül:

**`docs/voice-module-handoff.md`** ← ide másold be a `projekt_atadas.md`
tartalmát a repóból.

Ez a fájl csak azt rögzíti, ami a **backend-integrációt** érinti — a
hangmodul fejlesztési részleteit a fenti dokumentum tartalmazza, azt ne
duplikáld ide.

## A határ: feltöltés a felvétel végén, nem élő hangfolyam (2026-10, V26–V28)

Korábbi terv: böngésző → WebSocket → Python, a Spring csak a végeredményt kapja. **Módosítva**:

```
Böngésző (MediaRecorder, WebM/Opus) ──multipart──▶ Spring (data/documents/{beteg}/hangfelvetelek/, gitignore)
   Spring ──{fájl útja, mód, az orvos hanglenyomata}──▶ Python hangszolgáltatás (ai-service/voice/service.py, :8002)
   Python: Whisper → pyannote → szerepkiosztás → MedGemma-átnézés → kulcsszavak
   Python ──callback (X-Internal-Token)──▶ Spring: megszólalások + kulcsszó-javaslatok, egy tranzakcióban
   Spring: commit után összefoglalót kér (Python, ugyanaz a sor); az eredeti hang megmarad
```

Oka: (1) a böngésző nem kaphatja meg a belső tokent, a Python pedig nem végez autentikációt — a
Springen át a hozzáférési napló és a betegazonosítás is a meglévő úton működik; (2) a prototípus
fájl-alapú (offline Whisper + diarizáció), élő átírás nincs; (3) ugyanaz a minta, mint a
dokumentum-feldolgozásnál (`docs/document-processing.md`). A Spring nem audio-relay: a hang a
lemezre kerül, a Python a **fájl útját** kapja (egy gépen fut a kettő). **Az eredeti hangfelvétel
megmarad** (a felhasználó döntése, 2026-10-13): a beteg dokumentum-mappájában,
`{documents-dir}/{patientId}/hangfelvetelek/{uuid}.{webm|ogg|m4a|wav}`, az útja (a documents-dir-hez
relatívan) a `recording.audio_path`. A `recording.audio_deleted_at` csak a korábbi (törölt hangú)
felvételeknél van kitöltve. Ismert rés (mint a dokumentumoknál): a beteg törlésekor a fájl a lemezen marad.

A Spring sosem hívja az Ollamát. GPU (8 GB): a Python saját soros sora + a docproc-kal közös
folyamatközi fájlzár (`ai-service/gpulock.py`) — egyszerre egy GPU-feladat fut a gépen; a Whisper és
a diarizáció modellje a lépés után felszabadul, hogy a MedGemma elférjen.

## Hangprofil (V26) — felvétel feltétele

- A Profil oldalon a böngészőben: 2 mp csend (zajszint), majd a prototípus 8 mondata
  (`profil_core.ENROLL_SENTENCES`), mondatonként azonnali durva ellenőrzéssel (hossz, hangerő).
- `POST /api/voice-profiles` (multipart: `userId`, `sentences[]`, `noise`, `microphone`) → 202,
  `voice_profile.status = PROCESSING`, nem aktív. Csak `DOCTOR`. Felhasználónként egy készülő profil.
- Python (`enrollment.py`, a CLI-vel közös `profil_core.py`): VAD, mondatonkénti minőségellenőrzés,
  összhang-ellenőrzés (a többitől eltérő hangú mondat kimarad), hanglenyomat (wespeaker, 256 dim),
  a kiszűrt beszéd `profile.wav`-ként marad (modellváltáskor újraszámolható); a mondat-fájlok törlődnek.
- Callback → `READY` + aktív (a régi aktív inaktív lesz), vagy `FAILED` az okkal.
- **Felvétel csak használható profillal** (aktív, `READY`, van hanglenyomat): különben 409
  (`VoiceProfileRequiredException`), a frontenden zárolt üzenet. A V4-es, kézzel rögzített
  (hanglenyomat nélküli) profilokat újra kell venni.
- A hanglenyomat a válaszban nem szerepel (biometrikus adat).

## Felvétel feldolgozása (V27)

- `POST /api/recordings` (multipart: `patientId`, `userId`, `medicalCareId?`, `mode`, `durationSec`,
  `audio`) → 202, a felvétel rögtön `PROCESSING`. A profil a felvételkor aktív, nem a kliensé. A hang
  sikeres és sikertelen feldolgozás után is megmarad (újra feldolgozható, visszahallgatható).
- A régi JSON-os `POST /api/recordings` + `PATCH …/status` + `POST …/utterances` megmaradt a
  CLI-prototípus kimenetének kézi importjához (a frontend a „kézi” felvételnél ezt mutatja).
- Python (`transcription.py`): faster-whisper (szó-valószínűséggel), pyannote diarizáció
  (memóriában dekódolt hanggal), szerepkiosztás az orvos hanglenyomatához mérve, és
  **megszólalásonkénti** hasonlóság (`utterance.similarity`, ≥ 1,5 mp-es megszólalásnál).
  Ha a legjobb hasonlóság is 0,3 alatt van, figyelmeztetés (a küszöb még nincs behangolva).
  Az sbo mód fix 2 beszélőt kér: ha a „másik” beszélő is ≥ 0,75-ös hasonlóságú az orvoshoz (mért:
  egybeszélős felvételen 0,96), az is az orvos hangja — figyelmeztetéssel összevonjuk.

## MedGemma-átnézés (`review.py`)

**Célzott kérdések, nem „javítsd az egészet”** — mérve: a teljes átiratot kapó 4B modell vagy
semmit sem javított, vagy tartalmat hagyott el. A bizonytalan helyeket determinisztikusan jelöljük ki:

| Kérdés | Mikor | Mit tehet a modell |
|---|---|---|
| szó | Whisper szó-valószínűség < 0,5 | a szakaszt cserélheti (szétszakadt szónál választhat: «rami prilt» / «ramiprilt») |
| beszélő | a megszólalás hangja ±0,2-vel eltér a beszélője átlagától, vagy a tartalom nem illik a szerephez | orvos / beteg / egyéb |
| érték | echo: **minden** szám+mértékegység; sbo: a bizonytalan | **nem írhatja át**, csak „gyanús” jelölés → `review_note` |

Őrök (a modell válasza után, kódban): szám, mértékegység, tagadás nem változhat; a csere legfeljebb
2 szóval hosszabb és hasonlít az eredetire. Elvetett javítás → figyelmeztetés. Az eredeti ASR-szöveg
és -beszélő az `utterance.asr_text` / `asr_speaker`-ben marad, a `corrected_by` + `correction_reason`
mutatja, ki és miért javította; a frontend „AI-javított” jelöléssel és az eredeti megnézhetőségével
mutatja. **Ismert korlát**: a megszólalást nem bonthatja (ha a diarizáció egy kérdést és a választ
egy megszólalásba vonta, az egész egy beszélőé marad). Visszaállítás az eredetire jelenleg nincs
(a megszólalás megváltoztathatatlan).

Modell: `VOICE_REVIEW_MODEL` (üresen az `LLM_MODEL`; ajánlott: `medgemma:latest`). Mérés (2026-10-12, 8 megszólalásos
próba): `medgemma:latest` — 3 szójavítás + 1 beszélőcsere helyes, az echo „555 százalék” gyanúsként
jelölve; `medgemma1.5:4b` — 1 javítás, beszélőcsere nincs, a válaszai többször a kérdéshez sem illettek.
Ha az Ollama nem érhető el, a felvétel javítás nélkül kész lesz (figyelmeztetéssel).

## Kulcsszavak és összefoglaló (V28)

Tudatosan **külön táblák, nem `statement`** (mint a `clinical_summary`).

- **Kulcsszó** (`recording_keyword`): a MedGemma javasolja (`MODEL`, `SUGGESTED` az orvos döntéséig),
  vagy az orvos adja hozzá a + gombbal / kijelölésből (`DOCTOR`, `ACCEPTED`). A javaslat csak akkor
  marad meg, ha elhangzott: szótő-alapú visszakeresés (minden szó első 5 betűje, egymás után), és
  **az átiratban álló alakot** tároljuk (nem a modellét). A frontend kiemelése ugyanezt a szabályt
  használja (`lib/atiratKiemeles.tsx`).
- **Összefoglaló** (`recording_summary`): a felvétel elkészültekor automatikusan, utána gombbal
  újragenerálható (az előzmény megmarad). Bemenet: a teljes (javított) átirat + a nem elutasított
  kulcsszavak (pillanatkép). A modell mondatonként megszólalás-sorszámra hivatkozik; a Python és a
  Spring is ellenőrzi, hogy a hivatkozott `turn_id` létezik. Forrás nélküli mondat, az átiratban nem
  szereplő szám, kimaradt kulcsszó → figyelmeztetés.
- **Echo-diktálásnál csak az orvos** (a felhasználó döntése, 2026-10-15): `echo` módban a modell csak a
  `DOCTOR` megszólalásokat kapja, és csak ezekre hivatkozhat; a beteg / egyéb megszólalás kimarad
  (figyelmeztetéssel, hány). Ha nincs orvosi megszólalás, az összefoglaló `FAILED`. Az `sbo` mód
  változatlan (ott a beteg elmondása a lényeg). A beszélő-hozzárendelés a diarizációé + az átnézésé: ha
  az rossz, rossz megszólalás marad ki.
- **Szerkesztés (V29)**: az orvos átírhatja (`PUT /api/recordings/{id}/summaries/{sid}/edited-text`, csak
  `DOCTOR`, csak `DONE`). Az AI eredeti szövege és hivatkozásai (`summary`, `points`) nem változnak —
  az `edited_text` kerül előre („Orvos szerkesztette”), az eredeti lenyitható. Újraszerkesztés
  felülírja az előző szerkesztést; újragenerálás új sort ad (a szerkesztést nem viszi át).

## Csoportok (V34)

Az átiratból hat csoport, mindegyik az orvos szerkeszthető listája: **Panaszok** (+ BNO), **Diagnózisok** (+ BNO),
**Beutalók**, **Vizsgálati megállapítások**, **Javasolt gyógyszerek**, **Javaslatok / kontroll**. Szabi kérése
(2026-10-23). Tudatosan **külön táblák, nem `statement`** (mint a kulcsszó és az összefoglaló).

- **Folyamat**: a felvétel elkészültekor (`RecordingTranscribedEvent`) automatikusan, utána gombbal újra
  (`POST /api/recordings/{id}/findings/extractions`, 202). Spring → Python `POST /recording-findings` (a teljes,
  javított átirat) → `findings.py` (MedGemma, kötött JSON-séma) → callback `POST /api/recording-extractions/{id}/result`.
- **Determinisztikus ellenőrzés (Python)**: minden elem megszólalásra hivatkozik; rossz / hiányzó sorszámnál
  szótő-egyezés (`summary.ground`), ha így sincs forrás, **az elem kimarad** (figyelmeztetéssel). Tagadott tünet
  (`nincs…`, `tagad…`) nem panasz. Az átiratban nem szereplő szám → „Ellenőrizendő” jelölés az elemen. A Spring
  újra ellenőrzi, hogy a `turn_id`-k léteznek. Echo-diktálásnál csak az orvos megszólalásai (mint az összefoglalónál).
- **BNO-kód**: a modell nem ad. Csak ha a hivatkozott megszólalásban a „BNO” szó mellett szó szerint elhangzott
  (pl. „BNO I20.0”) és pontosan egy ilyen kód van; különben az orvos írja be. Formátum-ellenőrzés (DB CHECK +
  entitás), kódtábla még nincs — létező kódra nem ellenőrzünk.
- **Az orvos**: hozzáad (`POST …/findings`; a + gombbal, vagy az átirat kijelöléséből „Csoportba…” — ekkor a
  megszólalás a forrás), szerkeszt (`PUT …/findings/{fid}`: szöveg + BNO), töröl (`POST …/findings/{fid}/delete`,
  logikai). Csak `DOCTOR`, csak kész felvétel. Az AI eredetije megmarad, a frontenden lenyitható.
- **Újrakinyerés**: a legfrissebb KÉSZ futás AI-elemei jelennek meg + az orvos elemei. A korábbi futás AI-elemei
  (és a rajtuk végzett szerkesztés) az adatbázisban maradnak, a listából eltűnnek — a frontend erre rákérdez.
- **Időkorlát**: `FINDINGS_DEADLINE` (alapból max(`LLM_DEADLINE`, 600) mp). Mérés (2026-10-23, 8 megszólalásos
  szintetikus SBO-próba): `medgemma:latest` (4B) 20–60 mp, mind a hat csoport helyes, egy panasz (nehézlégzés) és a
  vizsgálatkérés (EKG, troponin) kimaradt; `medgemma:27b` 120 mp alatt nem végzett.

## Leképezés a fő adatmodellre

| Hangmodul (Python) | Fő rendszer |
|---|---|
| `chat` megszólalások | `utterance` (+ `asr_*` eredeti, `review_note`) |
| ASR-modell, átnéző modell | `recording.asr_model`, `recording.review_model` |
| kulcsszó-javaslat | `recording_keyword` (`MODEL`) |
| összefoglaló | `recording_summary` (modell, seed, prompt-hash) |
| csoportok (`findings.py`) | `recording_extraction` (futás) + `recording_finding` (elem, `turn_ids`) |
| orvosi jóváhagyás | kulcsszónál `ACCEPTED`/`REJECTED`; állításnál `statement_approval` |

A prototípus `extract()`-je (tünet / gyógyszer / mérés → `statement` `MODEL_INFERENCE`, AUDIO
forrással) **még nincs bekötve** — következő lépés lehet.

## `doctor_id`

Az `app_user.id` — a hangprofil a felhasználói fiók adata (`voice_profile.user_id` FK). Bejelentkezés
még nincs: a `userId` a kérésből jön.

## Szerepkiosztás: háromértékű, nem bináris

`utterance.speaker`: `DOCTOR | PATIENT | OTHER` (a Python `orvos | beteg | egyéb` kimenete). Az SBO-eset
explicit tartalmaz hozzátartozót — kétértékű felosztásnál a hozzátartozó kijelentése kényszerűen a
beteghez kerülne, ami anamnézis-hibát okoz; ő az `OTHER`.

## Futtatás — internet nélkül

```
cd ai-service/voice
ai_env\Scripts\activate          # CUDA-s torch + requirements.txt
python download_models.py        # EGYSZER, internettel: ai-service/models/ (gitignore)
python service.py                # http://127.0.0.1:8002 — innentől offline
```

Futás közben **semmi nem töltődik le** (`local_models.py`): a modellek a projekt `ai-service/models/`
mappájából jönnek (faster-whisper, pyannote diarizáció, wespeaker), a Silero VAD a pip-csomagban van, az
LLM az Ollamán. A Hugging Face Hub offline módban fut (`HF_HUB_OFFLINE=1`), és **a pyannote telemetria
ki van kapcsolva** (`PYANNOTE_METRICS_ENABLED=false` — a pyannote 4 alapból az otel.pyannote.ai-ra küld
használati adatot). Hiányzó modellnél érthető hibával áll le, nem kezd letölteni. Futás közben a
`HF_TOKEN` sem kell, csak a letöltéshez.

A `.env`: `MEDIAI_AI_TOKEN`, `HF_TOKEN` (pyannote), `ASR_MODEL`, `VOICE_REVIEW_MODEL`,
`MEDIAI_VOICE_SERVICE_URL` (Spring), opcionálisan `MEDIAI_VOICE_ROOT` (a Spring `data/voice`-a: a
hangprofil-minták) és `MEDIAI_DOCUMENTS_ROOT` (a `data/documents`: a felvételek) abszolút úttal — ha
bármelyik meg van adva, csak ezek alatti fájl olvasható.
