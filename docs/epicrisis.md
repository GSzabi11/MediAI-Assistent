# Epikrízis — V33

Egy **ellátáshoz** (`medical_care`) készül, az Ellátások fülön az ellátás „Epikrízis” gombjával. Bemenet: az ellátás
leletei, az ellátás kész hangfelvételeinek átirata és a beteg aktív gyógyszerlistája — az orvos csak az ellátást
választja ki (a felhasználó döntése, 2026-10-23).

## Sablon (a felhasználó által megadott, pontosan)

```
Beteg: Kovács István (sz.: 1958-03-14)     ┐
TAJ: 123-456-789                           │ fejléc — a Spring tölti az adatbázisból, NEM a modell;
Dátum: 2026. 10. 15.                       ┘ a felületen és a szerkesztőben nem szerkeszthető

DIAGNÓZISOK:                 BNO – név; a leletek kivonatából (BNO csak a leletből) + a modell az átiratból
KRITIKUS MEGÁLLAPÍTÁSOK:     • a modell, pontonként forrással (lelet / megszólalás) és szó szerinti idézettel
JAVASLATOK:                  • a modell, feltételes mód, legfeljebb 6
```

- **Dátum**: a lezárt ellátás zárásának napja, a nyitotté a kezdetéé (budapesti idő).
- Azonosítatlan betegnél „Azonosítatlan beteg”, TAJ nélkül „–”, születési dátum nélkül a „(sz.: …)” elmarad.
- Az aktuális gyógyszerek a javaslatok bemenete (pl. adag-felülvizsgálat), a sablonban nincs külön szakaszuk; a kéréskor
  aktív lista pillanatképe az `epicrisis.medications`-ben rögzül.

## Tudatos döntések

- **Külön tábla, nem `statement`** — mint a `medical_history` (a felhasználó döntése). Amit megőrzünk: MIBŐL (`input`:
  lelet-, laborsor-, felvétel-, gyógyszer-, allergia-azonosítók), HOGY (modell, seed, prompt-hash, verzió), MIKOR, és
  MERRE: minden megállapítás és diagnózis a forrására mutat (`documentId` vagy `recordingId` + `turnIds`); a Spring
  ellenőrzi, hogy a bemenet része volt, és hogy a megszólalás létezik.
- **Az ellátás leletei** (egyelőre): a használható leletek, amelyek klinikai dátuma az ellátás kezdete és zárása
  (nyitottnál a mai nap) napja közé esik, budapesti idő szerint (`EpicrisisFactsBuilder.inCarePeriod`). Az orvos nem
  válogat. Tartós lelet ↔ ellátás hozzárendelés (`document.medical_care_id`) egyelőre nem kell (a felhasználó
  döntése, 2026-10-23) — ha később kell, azzal egy korábbi lelet is az ellátáshoz köthető. A felhasznált leletek az `input.documentIds`-ben rögzülnek.
- **Közös dokumentum-kivonat** a kórtörténettel (`document_digest`, `history.make_digest`): ami már elkészült, nem
  számolódik újra; az epikrízis által készített kivonatot a kórtörténet is használja.
- Ellátásonként több epikrízis lehet (újragenerálás = új sor), egyszerre egy `PROCESSING` (részleges egyedi index).
- Összetett FK `(medical_care_id, patient_id) → medical_care(id, patient_id)`: az epikrízis betege csak az ellátás
  betege lehet.

## Folyamat

```
POST /api/epicrises {medicalCareId, userId}   → 202, PROCESSING
  Spring: EpicrisisFactsBuilder — az ellátás leletei (MedicalHistoryFactsBuilder szűrővel: szöveg + meglévő
          kivonat; laborleletnél a szabálymotor szerinti eltérések), az ellátás DONE felvételeinek megszólalásai
          (felvételenként ≤ 12 000 karakter), gyógyszer, allergia; név/TAJ maszkolva ("[beteg]", "[TAJ]")
  commit után @Async → POST {ai-service}/epicrises
Python (epicrisis.py, a GPU-sorban): hiányzó kivonatok → diagnózisok a kivonatokból (determinisztikus
  összefésülés) → kritikus megállapítások + átirat-diagnózisok (JSON-séma) → javaslatok (JSON-séma) → ellenőrzés
  → POST /api/epicrises/{id}/result {diagnoses, findings, recommendations, warnings, digests, model}
GET  /api/epicrises?patientId=              — a beteg összes epikrízise (a felület ellátásonként szűr)
PUT  /api/epicrises/{id}/edited-html        — az orvos szerkesztése (csak DOCTOR, csak DONE; a fejléc nem része)
GET  /api/epicrises/{id}/file               — DOCX (a hozzáférési naplóban EXPORT)
POST /api/epicrises/{id}/progress           — haladás (belső token), mint a kórtörténetnél
```

A modell a bemenetben `[D12]` (lelet) és `[H5:3]` (5-ös felvétel 3-as megszólalása) azonosítókat lát, és ezekkel
hivatkozik. Beragadt feladat (a docproc leállt): mint a kórtörténetnél, a lista lekérésekor a 60 mp-nél régebbi
`PROCESSING` sor `FAILED` lesz, ha a docproc `/health` `pending` listájában nincs `epicrisis:{id}`.

## Ellenőrzés (determinisztikus, sosem csendes)

- **Megállapítás**: érvénytelen / hiányzó forrás → „Ellenőrizendő: Nincs érvényes forrás-hivatkozás”; az idézet nincs
  szó szerint a hivatkozott leletben / megszólalásban → „Ellenőrizendő”; a forrásban nem szereplő szám → „Ellenőrizendő”.
- **Szakmailag szokatlan tartalom** (alkoholfogyasztás javaslata, `history.suspicious`): a megállapítás
  „Ellenőrizendő” jelölést kap (az átiratban elhangzottat is jelöljük — a döntés az orvosé); javaslatból kimarad.
- Az ellenőrizendő megállapítás nem megy a javaslatok bemenetébe, és a szövegben `[Ellenőrizendő: …]` előtaggal áll —
  exportba is, amíg az orvos ki nem törli.
- **Diagnózis**: a leletekből a kivonat szabályai szerint (idézettel; BNO-kód csak ha a leletben szó szerint áll); a
  modell az átiratból csak szó szerinti idézettel adhat diagnózist, BNO-kódot nem.
- **Javaslat**: gyógyszeradag-eltérés a listától, ellenőrizetlen szám → figyelmeztetés.

## Szerkesztés és export

A közös szerkesztő (`DokSzerkeszto`, a Medical historyval közös): félkövér, dőlt, aláhúzott, felsorolás, számozott
lista; a Spring szűri (ugyanaz az allowlist, `MedicalHistoryHtml.sanitize`). Az AI eredetije megmarad, megnézhető,
visszaállítható. A „Források (AI eredeti)” rész a megállapítások és diagnózisok forrás-linkjeit mutatja (lelet, ill.
átirat a kiemelt megszólalásokkal). DOCX: Word altChunk (`MedicalHistoryDocx.fromHtml`), „EPIKRÍZIS” cím + fejléc +
törzs + lábléc (AI által előkészített / orvos által szerkesztett). Mentetlen szerkesztést az export előbb elment.

## Modell

`HISTORY_LLM_MODEL` (ugyanaz, mint a kórtörténeté — a kivonatok emiatt közösek).

## Ismert korlátok

- Csak az ellátás időszakába eső lelet kerül be; egy korábbi lelet (pl. előző heti labor) nem.
- Felvétel csak akkor kerül be, ha az ellátáshoz van rendelve és `DONE`; az átiratból felvételenként legfeljebb
  12 000 karakter (figyelmeztetéssel).
- A javaslatok szakmai helyességét semmi nem ellenőrzi — feltételes mód, „a döntés az orvosé”.
