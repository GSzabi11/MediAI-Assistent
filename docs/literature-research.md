# Szakirodalmi kutatás (V30)

Rendeltetés: szakirodalom-visszakeresés egy konkrét beteghez. A rendszer nem diagnosztizál és nem
javasol terápiát; a cikkek összefoglalója és a „miért releváns” indoklás modell-kimenet, a döntés az orvosé.

## Hálózat: a tudatos kivétel

Az architecture.md 1. elve (on-premise, nincs külső hívás betegadattal) alól **ez az egyetlen funkció
kivétel** (a felhasználó döntése, 2026-10-20): a gépről kimegy a **keresőkifejezés**, betegadat nem.

- Kimenő kérés csak az `ai-service/docproc/literature.py`-ban van: NCBI E-utilities (esearch, efetch),
  Europe PMC (`searchPOST`), NIH iCite (hivatkozási szám, PMID-listával). A kifejezés POST-törzsben megy (nem kerül URL-be).
- A kifejezést az orvos látja és szerkeszti, mielőtt elmegy. A Spring `OutboundQueryGate` elutasítja
  (400), ha benne van a beteg neve, anyja neve, lakcíme, TAJ-a, születési dátuma, egy hosszú számsor
  vagy pontos életkor. A modell javaslatából a Python előre levágja a számsorokat és az életkort.
- A betegösszefoglaló, a kiegészítő kérés és az indoklás **csak a helyi modellhez** megy.
- Ismert rés: a forrásoldalak naplójában a gép IP-címe és a kifejezés látszik.

## Források

| Igény | Megoldás | Miért |
|---|---|---|
| PubMed | E-utilities, relevancia-sorrend | hivatalos API |
| Cochrane | ugyanaz a PubMed-keresés `"Cochrane Database Syst Rev"[Journal]`-ra szűrve | a Cochrane Library saját API-ja kulcshoz/licenchez kötött |
| Google Scholar helyett | Europe PMC szabad szavas keresés (kulcs nélkül, `searchPOST`), a mezőjelölők (`[MeSH]`) nélkül, csak PMID-es (`SRC:MED`) találat; a Scholarra kézi „megnyitás” link | a Scholarnak nincs API-ja, az ÁSZF tiltja a gépi lekérdezést (a felhasználó jóváhagyta, 2026-10-20) |
| Hivatkozási szám | iCite (kulcs nélkül) | az OpenAlex 2026 óta kulcsot és napi keretet kér; a forrás a felületen kiírva, hiányzó szám = „nincs adat”, nem 0 |

A Cochrane-áttekintések elöl állnak (más forrás mellett legfeljebb a hely fele), utána a PubMed és az
Europe PMC relevancia-sorrendje felváltva, PMID szerint egyesítve. Az Europe PMC kiesése nem buktatja el a
keresést, ha van más forrás (az állapotüzenet kiírja). `NCBI_API_KEY` nélkül 3 kérés/s (a kód tartja).

## Folyamat

```
Orvos: [AI-összefoglaló] → POST /api/research-contexts → Python (RESEARCH_LLM_MODEL): összefoglaló
       + angol keresőkifejezés → callback
Orvos: átírja (PUT …/edited-text, az AI eredetije megmarad), kiegészítő kérés, források → [Kutatás indítása]
Spring: POST /api/research → OutboundQueryGate → Python /research-searches (külön szál, NEM a GPU-sor)
Python: progress callbackek („PubMed: 245 találat; cikkadatok letöltése…”) → /result a cikkekkel
Spring: az 1. oldal (10 cikk) értékelése indul → Python GPU-sor, cikkenként egy feladat, callback cikkenként
Frontend: lapozáskor a megnyitott oldal még nem értékelt cikkeit kéri (POST …/evaluations)
```

## Modellek

- **A teljes funkció: `RESEARCH_LLM_MODEL`, alapból `qwen3.5:9b`** (a felhasználó döntése, 2026-10-20). Átbocsátás
  a lényeg: a `medgemma:27b` ~3 perc/hívás, egy oldal ~30 perc lenne. Mérve (2026-10-20, `medgemma:latest`
  4B): ~10 s/cikk; a nyelvtani hosszkorlát (`maxLength`) nélkül a 4B az összefoglalóban hurkolt, és az
  ismétlés-büntetés a JSON-kimenetnél rontott. A 4B relevancia-besorolása laza volt (EF > 40%-os vizsgálat
  „HIGH” EF 30%-os betegnél) — a qwen3.5:9b mérése még hátra van.
- A betegösszefoglaló is ezzel készül (korábban a 27b-vel: az a béta-blokkolóból hipertóniát
  következtetett — a prompt azóta tiltja; az orvos úgyis átírja).
- Őrök: absztrakt nélkül nincs értékelés; az összefoglaló számai az absztraktban (`unverified_numbers`);
  ismétlődő mondat kimarad; a csonka mondat levágva. A szerzőt, évet, PMID-et, hivatkozást sosem a modell adja.

## Adatmodell (V30)

Tudatosan **külön táblák, nem `statement`** (mint a `clinical_summary`): ezek keresési segédanyagok, nem a
betegről szóló, jóváhagyandó állítások. Mindhárom beteg-gyerektábla (`ON DELETE CASCADE`).

- `research_context`: AI-szöveg + kifejezés, az orvos szerkesztése mellettük (`edited_*`, csak DONE-nál),
  `input` (mely tényekből), modell/seed/prompt-hash. Betegenként egy készülő.
- `research`: a keresés **pillanatképe** (`context_text`, `extra_prompt`, `query`, `sources`) — a kontextus
  későbbi szerkesztése nem írja át, mihez mérte a modell a relevanciát. `SEARCHING → DONE | FAILED`, `progress`.
- `research_article`: bibliográfiai adat szó szerint a forrásból; értékelés cikkenként (`NONE → PENDING →
  DONE | FAILED`, a 15 percnél régebbi PENDING újrakérhető); `excluded`, `bookmarked` az orvos döntése.
  A könyvjelzők betegenként visszanézhetők (`GET /api/research/bookmarks?patientId=`).

## Nyitott kérdések

- Kimenő napló (mi ment ki, mikor, ki hagyta jóvá) még nincs; most a `research.query` + `access_log` mutatja.
- A kapu tudatosan nem szótár-alapú (a felhasználó döntése, 2026-10-20): az orvos mindig látja és
  szerkeszti a kifejezést küldés előtt, a kapu az ismert azonosítókat szűri (név, TAJ, születési dátum,
  lakcím, életkor). Automatikus (jóváhagyás nélküli) küldés nincs.
