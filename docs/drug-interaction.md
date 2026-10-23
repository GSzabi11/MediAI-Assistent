# Gyógyszer-interakció (V35)

Szabi kérése (2026-10-23): a beteg szedett gyógyszerei közötti interakció figyelése, és ad hoc ellenőrzés,
amikor az orvos 1–2 új gyógyszert ír be. **Determinisztikus szabály, nem LLM** (CLAUDE.md) — a riasztás
`DETERMINISTIC_RULE` állítás, a szabálymotor (`rule` modul) második szabálya a referencia-tartomány után.

## Források (Szabi döntése, 2026-10-23)

| Forrás | Mire | Licenc / megjegyzés |
|---|---|---|
| **NEAK PUPHA** (Publikus Gyógyszertörzs, havi) | magyar készítménynév → ATC-kód; az ATC-kódok magyar és angol neve | nyilvános; Access `.mdb` a zip-ben, Jackcess olvassa |
| **DDInter 2.0** | hatóanyag-párok interakciója, súlyossággal (Major / Moderate / Minor / Unknown) | **CC BY-NC-SA 4.0** — szakdolgozatra jó, kereskedelmi termékre nem; hivatkozandó: Tian et al., *NAR* 2024 |
| **RxNorm** (NLM RxNav) | ATC-kód → angol hatóanyagnév, ahol az ATC angol neve nem illeszkedik | csak a frissítéskor, csak az ATC-kód megy ki |

Elvetve: openFDA (csak a betegtájékoztató szabad szövege — párt csak LLM-mel lehetne kinyerni), RxNav
interakció-API (az NLM 2024-ben megszüntette), DrugBank (regisztrációhoz kötött, nem terjeszthető).

## Adatfolyam

```
havonta (3-án 03:30) / induláskor, ha üres / „Adatbázis frissítése” gomb
  DrugReferenceDownloader: NEAK oldal → legfrissebb PUPHA_ééééhhnn_vN.zip; DDInter 8 CSV (A,B,D,H,L,P,R,V)
      → data/drug-reference/ (gitignore); letöltési hiba esetén a korábbi helyi fájl
  PuphaReader (GYOGYSZ, ATCKONYV) + DdinterReader + RxNormAtcLookup (gyorsítótár: rxnorm-atc.tsv)
  ATC → DDInter-hatóanyag (atc_ddi_drug): az ATC angol / magyar nevének részei, RxNorm-nevek, kézi szinonimák
  EGY tranzakcióban cseréli: pupha_product, atc_code, ddi_drug, ddi_interaction, atc_ddi_drug
  → DrugReferenceRefreshedEvent → minden betegnél újraszámol

gyógyszer rögzítése / leállítása / dokumentum-feldolgozás (MedicationsChangedEvent, ugyanabban a tranzakcióban)
  DrugInteractionRule: a beteg aktív gyógyszerei → DrugResolver → DrugInteractionChecker → statement
```

Futás közben (a szabály és az API) **semmi nem megy ki a hálózatra**: minden a helyi táblákból jön. A frissítés
hív kifelé, betegadat nélkül (fájlletöltés + ATC-kód). A NEAK tanúsítványa a Microsec e-Szigno Root CA
2009-re vezet, ami a JDK truststore-jában nincs benne: a gyökér a `resources/certs`-ben van (SHA-256
`3C:5F:81:FE:…:F3:78`, a Windows-tárolóval egyeztetve), és csak a letöltő használja, a JDK gyökerei mellett.

## Név → hatóanyag (`DrugResolver`)

1. **Márkanév**: a név első, számot nem tartalmazó szavai (`brand_key`, ékezet nélkül) = PUPHA-készítmény
   márkakulcsa → ATC-kódok. Ha nincs találat, szavanként rövidítve (`ALGOFLEX FORTE DOLO` → `ALGOFLEX`),
   legalább 4 betűig. Ha van forgalomban lévő találat, csak azok számítanak.
2. **Hatóanyag mező** (és ha nincs PUPHA-találat, maga a név): részekre bontva (`+`, `/`, `,`, `és`, `and`),
   minden rész → DDInter-név vagy ATC-név (magyar / angol) a kulcs szerint.
3. ATC → DDInter-hatóanyagok.

**Nincs hasonlóság-alapú illesztés**: egy rossz hatóanyag hamis riasztást vagy hamis biztonságot adna. A kulcs:
kisbetű, ékezet / szóköz / írásjel nélkül (`DrugNames.key`); sóutótag (`sodium`, `hydrochloride`, …) csak akkor
marad le, ha a teljes név nem illeszkedik. A kézi szinonimák (`DrugNames.ALIASES`, pl. paracetamol →
acetaminophen) kézzel ellenőrzöttek; új pár = új sor.

Állapot: `RESOLVED` (minden rész illeszkedett), `PARTIAL` (pl. `perindopril and diuretics`: a „diuretics”
nem hatóanyag), `NO_DATA` (a készítmény ismert, de a DDInterben nincs — főleg EU-specifikus szerek, pl.
lornoxicam, aceclofenac, nadroparin), `NOT_FOUND`. **A frontend minden nem `RESOLVED` gyógyszert kiír**: ott a
riasztás hiánya nem jelenti, hogy nincs interakció.

## A szabály (`DrugInteractionRule`, `ruleId = drug-interaction`, v1)

- Gyógyszer-páronként (a beteg két aktív tétele) egy állítás, ha bármely hatóanyag-párjuk szerepel a DDInterben,
  vagy azonos a hatóanyaguk (kettőzés). Egy készítményen belüli hatóanyagok egymással nem riasztanak (szándékos
  kombináció).
- Szöveg (MI): `Marevan + Algoflex: súlyos interakció (Acenocoumarol – Ibuprofen). Forrás: DDInter 2.0`.
- Súlyosság: Major → `CRITICAL`; Moderate, kettőzés → `WARNING`; Minor, Unknown → `INFO`. Az „Unknown” (a párok
  ~20%-a: ismert, de klinikailag nem besorolt) figyelmeztetésként a lista nagy részét adná, és elfedné a valódi riasztásokat.
- MERRE: új forrástípus **`MEDICATION {medicationIds}`** (rendezett id-k); DB CHECK: csak szabály-állításé lehet,
  az API 400-zal elutasítja. Egyedi index: `(ruleId, medicationIds)`.
- MIKOR: a kiértékelés ideje (a gyógyszertételeknek nincs kezdő dátuma).
- Egyeztetés: ha egy gyógyszert leállítanak, a rá vonatkozó állítások törlődnek (a jóváhagyással együtt —
  CASCADE). Ha a frissítés megváltoztatja egy pár szövegét vagy súlyosságát, az állítás újra keletkezik (a
  korábbi jóváhagyás nem öröklődik). Idempotens; induláskor és minden frissítés után minden betegre lefut.
- Betöltetlen referenciaadatnál nem fut (a meglévő állítások maradnak), a frontend „nincs betöltve”-t mutat.

## API

| Végpont | Mit |
|---|---|
| `GET /api/drug-reference/status` | betöltve-e, fut-e frissítés, forrásonként a legutóbbi betöltés |
| `POST /api/drug-reference/refresh` | kézi frissítés → 202 (fut: 409) |
| `GET /api/drug-reference/suggestions?q=` | márkanév-javaslatok (a gyógyszernév nem betegadat) |
| `GET /api/patients/{id}/drug-interactions/coverage` | az aktív gyógyszerek feloldása (mihez van adat) |
| `POST /api/patients/{id}/drug-interactions/check` `{drugs: [..1–5]}` | ad hoc: a beírt szerek egymással és a szedettekkel; **nem tárolódik** |

A beteghez kötött végpontok a `/api/patients/{id}` alatt vannak, így a hozzáférési napló a beteget is rögzíti.

## Frontend

Gyógyszerek fül → **Interakciók** rész (`components/GyogyszerInterakcio.tsx`, `api/gyogyszerInterakcio.ts`): a
forrásadat állapota és frissítő gomb; a szedett gyógyszerek interakció-állításai (jóváhagyni a jobb oldali
Állítások listában lehet, ott „Gyógyszerlista” forrással); a nem (teljesen) ellenőrizhető gyógyszerek; az ad hoc
ellenőrzés (márkanév-javaslattal).

## Ismert korlátok

- A DDInter amerikai fókuszú: sok EU-s hatóanyag hiányzik (`NO_DATA`). Első betöltéskor (2026-10-23) a forgalomban
  lévő 1350 ATC-kódból 846-hoz volt DDInter-hatóanyag.
- A PUPHA a NEAK törzse: a nem nyilvántartott termékek (pl. gyógyászati segédeszköz műkönny) nincsenek benne — ezeknél a
  hatóanyag mező segít, ha ki van töltve.
- Gyógyszer–allergia ellenőrzés még nincs (külön szabály lenne).
- A gyógyszer–étel és a gyógyszer–betegség interakció (a DDInter weboldalán van) a letölthető fájlban nincs.
- Dózisfüggő interakciót nem vizsgálunk; a DDInter mechanizmus-leírása a tömeges letöltésben nincs benne.
