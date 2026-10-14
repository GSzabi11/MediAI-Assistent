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

## A modell bemenete és ellenőrzése

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
