# Klinikai státusz (AI-összefoglaló) — V23

A betegoldal első füle. Három rész:

| Rész | Forrás | Mód |
|---|---|---|
| Összefoglaló (3–4 mondat) | LLM (`summarize.py`) | gombra, a háttérben |
| Proaktív javaslatok (≤ 5) | LLM | ugyanazzal a gombbal |
| Fő eltérések (chipek) | **referencia-szabály** (`lab-reference-range` állítások) | determinisztikus, mindig friss |

## Tudatos döntés: külön tábla, nem `statement`

A felhasználó döntése: az összefoglaló a `clinical_summary` táblában él, **nem** állításként
— eltérés a CLAUDE.md központi elvétől (minden AI-kimenet `allitas`). Következmény: nincs
mondatonkénti forrás-visszakeresés és orvosi jóváhagyás. Amit megőrzünk:

- **MIBŐL**: `input` — a modellnek átadott laborsor-, állítás-, gyógyszer-, allergia- és
  dokumentum-azonosítók;
- **HOGY**: `model_name`, `seed`, `prompt_hash`, `extractor_version`;
- **MIKOR**: `requested_at`, `completed_at`, a vizsgált időszak (`period_start/end`, 6 hónap).

A felület kiírja: „AI-összefoglaló döntéstámogatásra — nem jóváhagyott orvosi állítás”.

## Folyamat

```
POST /api/clinical-summaries {patientId, userId}      → 202, status PROCESSING
  Spring: SummaryFactsBuilder — az elmúlt 6 hónap STRUKTURÁLT tényei (név/TAJ/lakcím nélkül)
  commit után @Async → POST {ai-service}/summaries {summaryId, callbackUrl, facts}
Python: ugyanabba a sorba, mint a dokumentum-feldolgozás (egyszerre egy GPU-feladat)
  két rövid szöveges hívás (összefoglaló, javaslatok) → ellenőrzés
  → POST /api/clinical-summaries/{id}/result {status, summary, recommendations, warnings, model}
GET /api/clinical-summaries?patientId=   — a legfrissebb elöl; stale = azóta új dokumentum/állítás
```

Betegenként egyszerre egy készülő összefoglaló (részleges egyedi index, 409). A Python nem
érhető el → FAILED; érvénytelen eredmény → FAILED az okkal.
**Beragadt feladat** (2026-10-23): ha a docproc a feldolgozás közben leáll, a sora (csak memóriában)
elveszik. A lista lekérésekor a Spring a 60 mp-nél régebbi `PROCESSING` sornál megkérdezi a docproc
`/health`-jének `pending` listáját; ha a `summary:{id}` nincs benne (vagy a docproc nem érhető el), FAILED
— ld. `docs/medical-history.md`.

## Hangfelvételek (2026-10-14, a felhasználó döntése)

Az összefoglaló és a javaslatok a dokumentumokból kinyert tényeken túl a hangfelvételek
**összefoglalóját** is megkapják: az időszak minden kész (`DONE`) felvételének legutóbbi elkészült
`recording_summary`-ja, az orvos szerkesztett szövegével, ha van (`SummaryFacts.recordings`,
`input.recordingSummaryIds`). Az átirat nem megy: a 4B-s modell kontextusát szétfeszítené, az
összefoglaló pedig már megszólalásokhoz kötött. Új vagy szerkesztett felvétel-összefoglaló után a
Klinikai státusz elavultnak látszik (`hasNewDataSince`).

**Az összefoglaló bemenete szűkebb, mint a javaslatoké** (a felhasználó döntése, 2026-10-14): az
összefoglaló csak a dokumentumok megállapításait (kinyert állítások, a gyógyszerek, allergiák) és a
felvétel-összefoglalókat kapja, a laborsorokat nem — a modell a laborlistát szó szerint
visszamondta, a laboreltérések pedig a Fő eltérések chipeken látszanak. A javaslatok a teljes
adatot kapják (laborral együtt). Az állítások a modellnek címke szerint rendezve mennek
(Diagnózis, Panasz, … elöl, Vizsgálat/Státusz hátul): a 4B a listán sorban haladt végig.

## Részletes összefoglaló (2026-10-15, a felhasználó kérése)

A 4 mondatos összefoglaló túl szűk volt: most 8–12 mondat, 2–4 bekezdésben (panasz és diagnózis;
vizsgálatok eredménnyel, dátummal; a hangfelvételeken elhangzottak; jelenlegi állapot, gyógyszer,
allergia), legfeljebb 900 token. A hurok-figyelés itt mondat-szintű (`_sentence_loop_start`, ≥ 6 szavas
ismétlődő mondat): a tagmondat-szintű a hosszabb szövegben hamisan jelzett, és a 2–3. mondatnál vágott.

- **Modell**: `SUMMARY_LLM_MODEL` (üresen `DOC_LLM_MODEL`). Mérés (12-es tesztbeteg): a `medgemma:latest`
  (4B) hosszabb szövegnél sablonos, ismétlő mondatokat adott („ismét felkereste … a palpitatios
  miatt”); a `medgemma:27b` jó, időrendi, bekezdésekre tagolt szöveget, de 8 GB VRAM-on ~3 perc
  hívásonként (két hívás: összefoglaló + javaslatok), ezalatt a GPU-sor (dokumentum-feldolgozás) vár.
- **Kulcsvizsgálatok** (`key_exams`): a vizsgálati leletek (típus a lelet fejlécéből: Holter, echo, MR, CT,
  ultrahang) dátummal és szó szerinti eredménnyel („Vélemény”, különben „Vizsgálat eredménye” a
  mérés-sorok nélkül) külön listában mennek a modellnek, „mindet említsd” utasítással. Oka: a 27b a
  részletes összefoglalóból az MR-t kihagyta, a Holtert csak előjegyzettként írta (a Holter-leletből a
  kinyerés semmit nem hozott). Ehhez a Spring a nem labor leletek szövegét is átadja
  (`DocumentFact.text`, ≤ 6000 karakter); a modellhez csak a kiolvasott eredmény megy, a beteg-fejléc
  nem. Ha az összefoglaló mégsem említ egy kulcsvizsgálatot, figyelmeztetés (`missing_exams`).
  A tagadások megtartását a prompt kéri (egy futásban a „nincs” elmaradt az echo-eredményből).
- **Gyógyszer-adag ellenőrzés** (`dose_mismatches`): ha az összefoglaló egy aktív gyógyszert a
  gyógyszerlistától eltérő adaggal említ (egy régebbi lelet adagját: „Bisoprolol 2,5 mg”, holott a
  lista szerint 1,25 mg), figyelmeztetés.


A 4B-s modell (`medgemma:latest`) a teljes laborlistán elveszett („a CRP emelkedett”, holott
normál) és hurokba esett. Ezért:

- **Bemenet**: csak a tartományon kívüli vizsgálatok részletes idősorral (a szabálymotor
  `[felett/alatt/eltér]` minősítésével); a normálok csak névvel, „nem kell kiemelni”
  jelöléssel; a leletekből kinyert állítások (elutasítottak nélkül), aktív gyógyszerek,
  allergiák.
- **Generálás**: két külön, rövid, egyszerű szöveges hívás (nem kötött JSON);
  `repeat_penalty 1.2`, `repeat_last_n 256`; streamelve — ismétlődő tagmondatnál azonnal
  leáll, és az utolsó teljes mondatig tartó rész marad.
- **Determinisztikus utóellenőrzés** (`warnings`, a felületen kiírva, nem csendes):
  ismétlődő mondatok elhagyása; „nincs kóros eltérés” mondat elhagyása, ha a szabály talált
  eltérést; ellentmondás, ha egy normál vizsgálatot eltérőnek nevez; ellenőrizetlen szám
  (tizedes vagy ≥ 10, ami a bemenetben nem szerepel — kitalált laborérték ellen).

## Ismert korlátok

- A kis modell szövege néha ismétlő vagy esetlen; az ellenőrzés a hamis állítást és a kitalált
  számot fogja meg, a stílust nem.
- A javaslatok orvosszakmai helyességét semmi nem ellenőrzi — ezért feltételes mód és a
  „döntés az orvosé” jelzés.
- A képen szereplő „Labor kérés / Beutalók / Kutatás indítása” gombok és a „Gyógyszer-
  interakció” chip kimaradt: nincs mögöttük funkció (interakció-motor sincs még).
