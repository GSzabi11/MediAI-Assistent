# Medical history (kórtörténeti összefoglaló) — V31

A betegoldal „Medical history” füle. Az összes feldolgozott dokumentumból készül:

| Rész | Forrás | Mód |
|---|---|---|
| Összefoglaló (elmúlt 6 hónap) | LLM — **ugyanaz, mint a Klinikai státuszé** (`summarize.write_summary`) | gombra, a háttérben |
| Kórtörténeti idővonal | laborlelet: **szabálymotor**; egyéb lelet: dokumentum-kivonat (LLM, idézettel ellenőrizve) | ugyanazzal a gombbal |
| Diagnózisok (BNO-kóddal) | dokumentum-kivonat; a **BNO-kód csak a leletből**, szó szerint | ugyanazzal a gombbal |
| Aktuális gyógyszerelés | a gyógyszerlista (`medication`, aktív) pillanatképe | determinisztikus |
| Javaslatok (≤ 6) | LLM, feltételes mód | ugyanazzal a gombbal |

A teljes szöveg egy rich text szerkesztőben jelenik meg (félkövér, dőlt, aláhúzott, felsorolás, számozott
lista); az orvos átírhatja és elmentheti, majd DOCX-be exportálhatja.

## Tudatos döntés: külön táblák, nem `statement` (a felhasználó döntése, 2026-10-23)

Mint a `clinical_summary`: a kórtörténet nem állítás, nincs mondatonkénti jóváhagyás. Amit megőrzünk:
MIBŐL (`input`: dokumentum-, laborsor-, gyógyszer-, allergia-azonosítók), HOGY (modell, seed, prompt-hash,
verzió), MIKOR (`requested_at`, `completed_at`), és MERRE: **minden idővonal-elem és diagnózis a
forrás-dokumentumára mutat** (`documentId`; a Spring ellenőrzi, hogy a bemenet része volt).

- `medical_history` — betegenként több sor (újragenerálás = új sor, az előzmény megmarad); egyszerre egy
  `PROCESSING` (részleges egyedi index). A gyógyszerlista a kéréskor rögzül (`medications`).
- `document_digest` — dokumentumonként egy kivonat. Összetett FK `(document_id, patient_id) → document`.

## Miért dokumentum-kivonat (digest)

A beteg teljes előzménye (pl. 16 ambuláns lap + 6 laborlelet) nem fér a 4B/27B modell kontextusába. Ezért
minden nem labor-dokumentumból **egyszer** készül kivonat (cím, hely, 1–2 mondat, alátámasztó idézet, korábbi
események az anamnézisből, diagnózisok), a Spring eltárolja, és a kórtörténet ezekből épül. Újragenerálásnál
csak az új dokumentumok kivonata készül el; promptváltáskor (`prompt_hash`, benne a modell neve) mind újra.
A laborleletnél nincs LLM: az idővonal-elem a referencia-szabály szerinti eltérések listája.

## Folyamat

```
POST /api/medical-histories {patientId, userId}     → 202, status PROCESSING
  Spring: MedicalHistoryFactsBuilder — feldolgozott dokumentumok (a szövegben a beteg neve, anyja neve,
          TAJ-ja "[beteg]" / "[TAJ]"-ra cserélve), meglévő kivonatok, laborleletenként az eltérések,
          gyógyszer, allergia (név/TAJ/lakcím a modellhez nem megy)
  commit után @Async → POST {ai-service}/medical-histories {historyId, callbackUrl, facts}
Python (a GPU-sorban): hiányzó / elavult kivonatok (JSON-séma) → idővonal + diagnózisok (determinisztikus
  összefésülés) → összefoglaló (stream) + javaslatok (stream) → ellenőrzés
  → POST /api/medical-histories/{id}/result {summary, timeline, diagnoses, recommendations, warnings, digests, model}
GET  /api/medical-histories?patientId=          — a legfrissebb elöl; stale = azóta új dokumentum/állítás
PUT  /api/medical-histories/{id}/edited-html    — az orvos szerkesztése (csak DOCTOR, csak DONE)
GET  /api/medical-histories/{id}/file           — DOCX (a hozzáférési naplóban EXPORT)
```

## Ellenőrzés (determinisztikus, sosem csendes)

A modell csak javasol (`history.py`):

- **Idővonal-elem**: a kivonat idézetének szó szerint a leletben kell lennie (ékezet/kisbetű/whitespace-
  független); ha nincs, az elem „Ellenőrizendő” jelölést kap. A leletben nem szereplő szám is jelölést ad.
- **Szakmailag szokatlan tartalom**: alkoholfogyasztás javaslata (szólista + tiltó szavak kizárása, pl.
  „igyon két sört” igen, „kerülje az alkoholt” nem) → „Ellenőrizendő”. Ha a leletben szó szerint így áll,
  akkor is jelöljük — a döntés az orvosé.
- **Ellenőrizendő elem nem megy az összefoglaló bemenetébe**, és a szerkesztő szövegében is
  `[Ellenőrizendő: …]` előtaggal áll — exportba is, amíg az orvos ki nem törli.
- **Cím**: a modell a prompt példájából vett át címet („Nephrologiai konzílium” egy kardiológiai leletre) —
  a cím érdemi szavának a leletben kell lennie, különben a dokumentumtípus neve.
- **Korábbi esemény / diagnózis**: csak szó szerinti idézettel marad meg; az esemény évének az idézetben kell
  állnia.
- **BNO-kód**: csak ha a leletben áll (pont és szóköz nélkül, a magyar ötjegyű írásmód záró nulláival:
  `N18.3` = `N1830`). A modell kódot nem adhat. Ha a modell a kódot írta névnek, a név az idézet kódon
  kívüli része lesz.
- **Hely** (intézmény/osztály): csak ha szó szerint a leletben áll.
- Javaslat: szokatlan tartalom kimarad; ellenőrizetlen szám, gyógyszeradag-eltérés → figyelmeztetés.

## Szerkesztés és export

- A szerkesztő HTML-jét a Spring szűri (jsoup allowlist: `p br div b strong i em u ul ol li h2 h3`, a `p`-n
  `class`) — script, stílus, esemény-attribútum, kép, link kimarad. Az AI eredetije (`summary`, `timeline`,
  …) nem változik; a felület „AI eredeti megtekintése” gombbal mutatja, „Visszaállítás” az AI szövegére tölt.
- **DOCX = Word „altChunk”** (mint a minta-fájl): a dokumentum egyetlen beágyazott HTML-részt (MHT) tartalmaz,
  amit a Word nyitáskor alakít át. Külön könyvtár nincs (`java.util.zip`). Fejléc: név, TAJ, születési dátum,
  „Készült” (a szerkesztés vagy az elkészülés napja); lábléc: AI által előkészített / orvos által szerkesztett.
  **Korlát**: a Word jól nyitja; a LibreOffice és a Google Docs az altChunkot gyengén vagy egyáltalán nem kezeli.
- Mentetlen szerkesztést az export előbb elment.

## Modell

`HISTORY_LLM_MODEL` (üresen a `SUMMARY_LLM_MODEL`, ill. `DOC_LLM_MODEL`). Próba (2026-10-23, 9-es tesztbeteg,
3 ambuláns + 5 laborlelet):
- `medgemma:latest` (4B): ~1 perc; a kivonatban a beteg nevét is leírta (→ maszkolás), egy kardiológiai
  leletnek a prompt példájából adott címet (→ cím-ellenőrzés), BNO-kódot névként adott (→ kód-név javítás);
  az összefoglalóban kitalált rövidítés-feloldás („nsVT (…)”) — a stílust/szakmai helyességet a
  determinisztikus ellenőrzés nem fogja meg.
- `medgemma:27b` (az alapértelmezés a `.env` szerint): ~10 perc 3 új kivonattal (meglévő kivonatokkal csak a
  két szöveges hívás, ~5–6 perc); a kivonatok és a BNO-kódok (`K2190`, `J3920` a leletből) helyesek, az
  összefoglaló időrendje nagyrészt jó, de egy diagnózis dátumát rosszul kötötte („októberben diagnosztizálták
  a refluxot”, holott februári). Ezalatt a GPU-sor (dokumentum-feldolgozás) vár.

## Összefoglaló = a Klinikai státusz összefoglalója (a felhasználó döntése, 2026-10-23)

Az összefoglaló csak az **elmúlt 6 hónap** tényeiből készül, a Klinikai státusszal azonos bemenettel
(`SummaryFactsBuilder`, `MedicalHistoryFacts.summaryFacts`), prompttal és ellenőrzéssel
(`summarize.write_summary` + `check_summary`), de a **proaktív javaslatok nélkül**. Az idővonal, a
diagnózisok és a kórtörténet saját Javaslatai továbbra is a teljes előzményből készülnek. Ha az elmúlt 6
hónapból nincs adat, modell nélküli rögzített szöveg áll ott. A korábbi, teljes előzményes anamnézis-prompt
(`SYSTEM_HISTORY`) megszűnt.

## Sorrend

Az idővonal a felületen, a szerkesztő szövegében és a DOCX-ben **a legfrissebb elemmel kezdődik** (a felhasználó
kérése, 2026-10-23). Tárolva és a modellnek időrendben megy; a megfordítás csak a megjelenítésben van
(`MedicalHistoryHtml.render`, `KortortenetFul`).

## Haladásjelző

A Python lépésenként jelez (`POST /api/medical-histories/{id}/progress {message, step, total}`, belső tokennel):
minden hiányzó dokumentum-kivonat egy lépés („Dokumentum-kivonat 5/16: 2026-02-17 ambuláns lap”), utána az
összefoglaló és a javaslatok. A Spring csak memóriában tartja (`MedicalHistoryProgressStore`, mint a chat), a
lista válaszában `progress` mezőként adja; a felület sávot és az eltelt időt mutatja. Spring-újraindításkor
elveszik, a következő lépés pótolja.

## Beragadt feladat (a docproc leállt / újraindult)

A Python sora csak memóriában él: ha a szolgáltatás leáll, a sorban álló vagy futó kórtörténet elveszik, a
callback sosem jön, és a sor `PROCESSING`-ben maradna („Készül…”, a gomb tiltva). Ezért a lista lekérésekor
(`GET /api/medical-histories?patientId=`, a felület 5 mp-enként hívja, amíg készül) a Spring a 60 mp-nél régebbi
`PROCESSING` sornál megkérdezi a docproc `/health`-jét (`pending`: a sorban álló + futó feladatok kulcsa, pl.
`medical-history:5`). Ha a feladat nincs ott, vagy a docproc nem érhető el, a sor `FAILED` lesz az okkal, és a
generálás újraindítható. Régi docproc (nincs `pending` mezője) esetén nem nyúl hozzá. A 60 mp a dispatch
újrapróbálásainak (~12 mp) türelmi ideje. Ugyanígy működik a Klinikai státusznál (`summary:{id}`,
`ClinicalSummaryService.failLostJobs`); a többi docproc-feladatnál (chat, kutatás) nincs bekötve.

## Ismert korlátok

- A hangfelvételek (felvétel-összefoglalók) nem kerülnek az idővonalra és az összefoglalóba.
- A dokumentum nélküli (kézzel rögzített) laborsor nem kerül az idővonalra (nincs forrás-dokumentuma).
- Az összefoglaló mondatai nem hivatkoznak forrásra (mint a Klinikai státusznál); a forrás az idővonalon van.
- A javaslatok szakmai helyességét semmi nem ellenőrzi — feltételes mód és „a döntés az orvosé”.
- Ismert rés (mint a dokumentumoknál): a beteg törlésekor a DB-sorok törlődnek, de a letöltött DOCX-ek nem
  kontrollálhatók.
