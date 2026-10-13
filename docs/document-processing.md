# Automatikus dokumentum-feldolgozás (Spring ↔ Python) — V17

Feltöltött PDF → szöveg, típus, dátum, RAG-darabok, laborsorok / állítások, orvosi
beavatkozás nélkül. Két folyamat, két irányú REST:

```
Orvos ── POST /api/documents (PDF) ──▶ Spring ── 202 Accepted, status = PROCESSING
                                         │  fájl: {patientId}/PROCESSING/{sha256}.pdf
                                         │  commit UTÁN, @Async:
                                         ▼
                              POST {ai-service}/jobs ──▶ Python (docproc)
                                                          │ sor (egyszerre 1 dokumentum, GPU)
                                                          │ PDF → szöveg (PyMuPDF, OCR nincs)
                                                          │ típus: kulcsszó-keresés (NEM modell)
                                                          │ Ollama, típusfüggő prompt + JSON-séma
                                                          │ determinisztikus visszakeresés a szövegben
                                                          │ darabolás + BGE-M3 embedding
                                                          ▼
Spring ◀── POST /api/documents/{id}/processing-result ── Python (callback, utána jön a következő)
  │ egy tranzakció: szöveg, típus, dátum, chunkok, labor / állítás + gyógyszer + allergia
  │ fájl → {patientId}/{LAB|AMBULANCE}/{sha256}.pdf, status = PROCESSED (vagy FAILED)
```

- **A Spring sosem hívja az Ollamát**: modellt, promptot, GPU-sort a Python kezel.
- **A sorbaállítás a Pythonban van** (`worker.JobQueue`): a Spring csak beadja a feladatot.
- **Hitelesítés**: mindkét irányban `X-Internal-Token` fejléc, közös titok
  (`mediai.ai-service.token` ↔ `MEDIAI_AI_TOKEN`), konstans idejű összevetéssel mindkét oldalon.
  **Nincs alapérték**: ha a `MEDIAI_AI_TOKEN` nincs beállítva, egyik szolgáltatás sem indul
  (fail-closed — a korábbi beégetett `dev-only-change-me` a repóban bárki számára ismert volt).
  Spring Security nélkül ez tartja távol a
  callbacktől a klienseket — enélkül bárki „feldolgozott” laborértéket írhatna egy beteghez.

## 1. Spring → Python: `POST {mediai.ai-service.url}/jobs`

Fejléc: `X-Internal-Token: <token>`

```json
{
  "documentId": 12,
  "filePath": "C:/…/data/documents/3/PROCESSING/9f2c…e1.pdf",
  "callbackUrl": "http://localhost:8080/api/documents/12/processing-result"
}
```

| Válasz | Jelentés |
|---|---|
| `202 {"documentId": 12, "queuePosition": 0}` | sorba állt (0 = következő) |
| `202 {"documentId": 12, "alreadyQueued": true}` | már sorban van / fut — nem dolgozza fel kétszer |
| `401` | rossz token |

A `filePath` abszolút út; a Python ugyanazon a gépen olvassa (közös fájlrendszer). Ha a
`MEDIAI_DOCUMENTS_ROOT` be van állítva, csak az alatti fájl dolgozható fel.

Ha a Python nem érhető el, a Spring 3× újrapróbál (2 s, 10 s, 30 s), utána a dokumentum
`FAILED` („Az AI-feldolgozó szolgáltatás nem érhető el…”).

## 2. Python → Spring: `POST /api/documents/{id}/processing-result`

Fejléc: `X-Internal-Token: <token>`. DTO: `processing/api/ProcessingResultRequest`.

### Sikeres feldolgozás

```json
{
  "status": "PROCESSED",
  "documentType": "LAB",
  "extractedText": "LABORATÓRIUMI LELET\nMintavétel: 2026.09.28.\n…",
  "clinicalDate": "2026-09-28",
  "extraction": {
    "modelName": "medgemma:4b",
    "seed": 42,
    "promptHash": "e625db39…(sha256 hex, 64)",
    "extractorVersion": "docproc-1"
  },
  "chunks": {
    "embeddingModel": "BAAI/bge-m3",
    "chunks": [{ "charStart": 0, "charEnd": 812, "embedding": [0.012, "…1024 szám"] }]
  },
  "labResults": [
    { "groupName": "Klinikai kémia", "name": "Kálium", "value": "5.9", "comparator": null, "valueText": null, "unit": "mmol/l",
      "refLow": "3.5", "refHigh": "5.1", "refText": null, "measuredAt": null, "code": null },
    { "groupName": "Vizelet", "name": "Fehérje (vizelet)", "value": null, "comparator": null,
      "valueText": "Negatív", "unit": null, "refLow": null, "refHigh": null, "refText": "Negatív",
      "measuredAt": null, "code": "50561-0" }
  ],
  "statements": [],
  "warnings": ["Laborsor kihagyva — nem numerikus (cenzorált/kvalitatív) érték: CRP = <0,5"]
}
```

AMBULANCE-nál `labResults: []`, és `statements`:

```json
{
  "text": "Amlodipin 5 mg naponta 1x1 tabletta",
  "label": "Gyógyszer",
  "severity": "INFO",
  "uncertainty": "LOW",
  "charStart": 333,
  "charEnd": 368,
  "medication": { "name": "Amlodipin", "activeIngredient": null, "dose": "5",
                  "doseUnit": "mg", "route": "naponta 1x1" },
  "allergy": null
}
```

- `documentType`: `LAB | AMBULANCE` (IMAGING nem jöhet).
- Minden offset a `extractedText`-re, **Unicode code pointban** (Python `str` index = Spring
  `Document.textSpan`).
- `clinicalDate` kötelező: a dokumentum szövegéből, nem a feltöltés idejéből.
- `labResults[]` = a meglévő `LabResultRequest` (`@Digits(8,4)`; `measuredAt: null` → a
  dokumentum dátuma budapesti éjfélkor). `statements[]`: `medication` és `allergy` közül
  legfeljebb az egyik.

### Sikertelen feldolgozás

```json
{ "status": "FAILED", "error": "A PDF-nek nincs szöveges rétege (szkennelt dokumentum?) — OCR nincs" }
```

### Válaszok

| Kód | Mikor | A dokumentum |
|---|---|---|
| `200` + `DocumentResponse` | rögzítve | `PROCESSED` / `FAILED` |
| `400` | érvénytelen eredmény (validáció, rossz offset, rossz embedding-dimenzió…) | **`FAILED`** az okkal — semmi sem került be félig |
| `401` | rossz token | változatlan |
| `404` | ismeretlen dokumentum | — |
| `409` | nem `PROCESSING` (pl. ismételt callback) | változatlan |

A validáció szándékosan a service-ben fut, nem `@Valid`-dal: egy érvénytelen eredmény is
`FAILED`-del zárja a dokumentumot, különben örökre `PROCESSING` maradna. A Python 5xx-nél /
hálózati hibánál újrapróbál; 400-nál még egy rövid `FAILED` callbacket küld (ha a Spring a
törzset sem tudta értelmezni).

## Modellhívás (llm.py)

- **Laborlelet szakaszonként** (25 táblázatsor / hívás), az eredmények összefésülve. Oka: egy
  ~50 soros leletnél a MedGemma 4B ~37 sor után ismétlő hurokba esett (a listát elölről
  kezdte), és a kontextus végéig generált → 10 perces időtúllépés, `FAILED`. Fix seed miatt
  ugyanaz a PDF mindig elbukott. A `qwen3.5:9b` nem esett hurokba, de 8 GB VRAM-on ~8 percig
  futott — modellváltás nem megoldás.
- **Ambuláns lap is szakaszonként** (bekezdéshatáron, ~2500 karakter / hívás, docproc-15). Oka: egy
  4 oldalas (12 ezer karakteres) lapnál a 4B az 1. oldal néhány eleme után hurokba esett, a végén
  álló „Javasolt gyógyszeres terápia” listáig el sem jutott (1 állítás, 0 gyógyszer). Előtte az
  oldalszámlálós élőláb (`Név  3/4  Esetszám: …`, ami az oldalszám nélkül többször ismétlődik) a
  körülötte álló üres sorokkal kimarad a modell bemenetéből, így az oldaltörés nem vág ketté
  listát. Az offsetek továbbra is az eredeti szövegen.
- **Gyógyszer a terápiás javaslatból is**: a `GYOGYSZER` mellett `TERAPIA`/`JAVASLAT` elemből is
  (a modell a javasolt terápia sorait gyakran javaslatnak sorolta). Determinisztikus pótlás a
  gyógyszerlista-sorból (`T. Controloc (pantoprazol) este 1x20 mg`): név, ha a modell nem adta,
  a zárójeles hatóanyag; a dózis `1x1,25` alakjából az egyszeri adag (`1,25`).
- **Streamelt hívás hurok-figyeléssel**: ha egy már kiadott elem (labor: név+érték, ambuláns:
  idézet) újra megjelenik, a hívás leáll, és a hurok előtti teljes elemek maradnak
  (figyelmeztetéssel). `num_predict` felső korlát (`DOC_LLM_MAX_OUTPUT_TOKENS`, 4096).
- A modell bemenetében a táblázat hosszú szóközsorai összevonva (~fele annyi token), a több
  sorba tört nevek folytatása (`(TSH)`, `térfoga`) visszafűzve az előző sorhoz. A visszakeresés
  és minden offset az eredeti szövegen fut.

## Determinisztikus rétegek (nem modell)

- **Sor helye a PDF-ben** (`locate.py`): a laborsorhoz a PyMuPDF szó-koordinátáiból azt a
  vizuális sort keresi, amelyben a vizsgálat nevének eleje és utána az érték áll, és a teljes
  sort `sourceBox`-ként küldi (`{page, x, y, width, height}`, normált). Ha nem találja,
  figyelmeztetés, és a sor kiemelés nélkül jelenik meg.

- **Típus** (`classify.py`): `laboratóriumi lelet` vagy `mikrobiológiai lelet` a szövegben →
  `LAB`, különben `AMBULANCE`. Kis/nagybetű-, ékezet-, whitespace- és sorvégi elválasztás-
  független.
- **Visszakeresés** (`extract.py`): a modell csak javasol. Laborsor csak akkor kerül be, ha a
  neve **és** az értéke a vizsgálat saját sorában szerepel (több sorba tört névnél elég, ha a
  név minden szava megvan); a referenciatartomány utáni mértékegység megengedett, a határértékek
  is visszakeresve. A **vizsgálati kód** (LOINC-szerű, pl. `22664-7`) nem a modellből jön, hanem
  determinisztikusan a sorból, a név elől (a modell a kódoszlopot egy sorral elcsúsztatta).
  Cenzorált érték (`>90`) → `value` 90 + `comparator` `>` (V18), csak ha a jel is a sorban áll;
  az érték után mértékegység állhat (`0 /HPF`). Kvalitatív érték (`Negatív`, `kevés`) → `valueText`
  (V19), csak ha a vizsgálat sorában a név UTÁN szó szerint áll; a szöveges referencia
  (`refText`), ha a modell nem adja, a sor oszlopaiból (az érték utáni cella, a `magas`/
  `alacsony` jelzést átugorva).
- **Egységes vizsgálatnév** (`canonical_lab_name`): a Synlab akkreditációs `(A)` jelölése
  levágva (`Kálium (K) (A)` → `Kálium (K)`); a nagybetűs `VIZELET …` főfejléc alatti sor neve
  `(vizelet)` végződést kap (`Cukor` → `Cukor (vizelet)`), mert a másik elrendezés így írja.
  Csoport: a modellé, ha a leletben létező fejléc, különben a lelet nagybetűs főfejléce
  (`KLINIKAI KÉMIA` → „Klinikai kémia”) — a modell gyakran a vizsgálat nevét adta csoportnak.
  A frontend és a `?name=` szűrő kis/nagybetű-független (`eGFR-EPI` = `EGFR-EPI`).
- **Oszlop-alapú ellenőrzés** (`textutil.cells`, a PDF-ben az oszlopokat ≥ 2 szóköz választja
  el): a több sorba tört cella folytatása (`gyűjtött (A)`, `számolható`) ahhoz a cellához kerül,
  amelyik fölött áll; az **eredmény csak a név utáni cellából** (Eredmény oszlop), a
  **referencia csak az azutáni cellákból** fogadható el — így a modell nem csúsztathatja a
  referenciát eredménynek (`Nem számolható` helyett `< 30.0`), sem a szomszéd sor referenciáját.
  A referencia elejére csúszott minősítés (`-`, `+`, `alacsony`, `magas`) levágva; mértékegység,
  ha a modell nem adta, a sorból. Ha a modell alcímet adott névnek és a teljes sort értéknek
  (`Automata mikroszkópos értékelés` = `Fehérvérsejt 0 < 4 /HPF`), vagy betű nélküli „nevet”
  (`>1030`, csoport `Fajsúly`), a valódi sorból olvasunk. Zárójeles egyezés csak nagybetűs
  rövidítésre (`(CRP)`). Egy táblázatsorból legfeljebb egy eredmény;
  a név szóhatáron egyezik, és előbb a zárójelen kívüli egyezések foglalnak sort, utána a
  zárójeles rövidítések (`C reaktív protein (CRP)` → „CRP”);
  kvalitatív érték (`negatív`) kimarad; állítás csak akkor, ha az idézete
  megtalálható (az offset a Python keresése, nem a modell állítása). Gyógyszer a listára csak
  akkor, ha a neve az idézetben van; a dózis csak ha az is ott van és belefér a
  `NUMERIC(10,3)`-ba. Hatóanyag csak ha a lelet maga írja (márkanév → hatóanyag: később
  determinisztikus, RxNorm/DDInter).
- **Dátum**: a modell dátuma csak ha a szövegben szerepel, és nem születési dátum („szül.” a
  sorában előtte); különben a szöveg egyetlen ilyen dátuma; különben `FAILED`.
- **Súlyosság**: a kategóriából (allergia → `WARNING`, más → `INFO`); a modell nem minősít.
- Minden kihagyás oka a `document.processing_warnings`-ba kerül — sosem csendes.

## Adatmodell-döntések (V17)

- `document.type` nullázható (PDF-nél a feldolgozás tölti); `status`
  `PROCESSING | PROCESSED | FAILED`, CHECK: feldolgozottnak van típusa, hibásnak van oka.
  A meglévő sorok `PROCESSED`-et kaptak.
- Állapotváltás csak atomikus natív `UPDATE … WHERE status = 'PROCESSING'`-gel: duplikált
  callback 0 sort módosít → 409.
- `extraction_model/seed/prompt_hash`, `extractor_version` a dokumentumon: a laborsoroknak
  **nincs saját állításuk** (tudatos döntés), a provenance-uk ez + `lab_result.document_id`.
- **Ambuláns adatok** = `MODEL_INFERENCE` állítások (`DOCUMENT` forrás, kötelező
  bizonytalanság, `MODEL` producer) — a meglévő `StatementService` ellenőrzi őket.
- **Gyógyszer / allergia azonnal, „nem ellenőrzött”-ként**: `origin`
  (`MANUAL | DOCUMENT_PIPELINE`, pipeline-nál kötelező a forrás-állítás). A `verified` NEM
  tárolt flag, hanem a forrás-állítás legfrissebb döntéséből számolódik (`ACCEPTED/MODIFIED`)
  — így egy későbbi elutasítás azonnal látszik. Ez **kivétel** a „csak jóváhagyott állításból”
  szabály alól, kizárólag a pipeline-nak; a kézi API-t továbbra is köti.
- Ismétlődés: meglévő allergiát nem vesz fel újra (figyelmeztetéssel). Azonos nevű aktív
  gyógyszernél (`MedicationService.createFromPipeline`, a felhasználó döntése, 2026-10-13): adag
  nélküli új tétel vagy ugyanaz az adag → nem veszi fel újra; adagos új tétel → a pipeline adag
  nélküli tétele leáll (nem blokkolja az adagosat); más adagnál a régebbi forrású (`clinicalTime`)
  adag leáll, az újabb aktív — régebbi dokumentum későbbi feldolgozásakor az ő adagja kerül fel
  eleve leállítottként. Azonos időnél (egy lapon „Gyógyszerei”, majd „Javasolt terápia”) a
  szövegben későbbi az érvényes. Kézi tételt a pipeline sosem állít le. Minden változás
  figyelmeztetésként a dokumentumon.
- Fájl-áthelyezés: másolás a tranzakcióban; a régi példány csak commit után törlődik,
  visszagörgetéskor az új. Betegnév nincs az útban (GDPR, névütközés).
- PDF feltöltésekor a kliens nem adhat típust/dátumot (400); nem PDF-nél a típus kötelező,
  és nincs automatikus feldolgozás (201, rögtön `PROCESSED`).

## Ismert rések

1. **Beragadt `PROCESSING`**: a Python sora memóriában van. Ha a Python feldolgozás közben
   újraindul, vagy a callback minden próbálkozásra elbukik, a dokumentum `PROCESSING`-ben
   marad; nincs automatikus újraküldés (reconciler).
2. **Hibás PDF nem tölthető fel újra**: `UNIQUE (patient_id, sha256)` — egy `FAILED`
   dokumentum ugyanazzal a fájllal nem indítható újra, és nincs újrafeldolgozó végpont (a
   feladat szerint kézi indítás nincs). Döntés kell: újrapróbálás, vagy `FAILED` sor cseréje.
3. **Elutasított forrás**: a pipeline-gyógyszer elutasítás után is a listán marad (aktív), csak
   `verified: false`; automatikus leállítás nincs.
4. Szkennelt PDF → `FAILED` (OCR nincs, a feladat szerint nem kell).
5. A PDF-szövegréteg sorrendje (`sort=True`) többhasábos leletnél keveredhet.
6. Az LLM a szöveg első `DOC_LLM_MAX_CHARS` (40 000) karakterét kapja (figyelmeztetéssel).
