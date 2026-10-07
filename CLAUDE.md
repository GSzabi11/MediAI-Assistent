# MediAI Assistant

Klinikai döntéstámogató rendszer, szakdolgozat (BME Mérnökinformatika BSc).
Cél: az orvos kognitív terhelésének csökkentése EESZT-szerű, szétszórt
betegadatból — magyar kórházi/sürgősségi kontextusban.

Részletes architektúra-indoklás: @docs/architecture.md
Adatmodell-döntések: @docs/schema-decisions.md
Hangmodul (külön prototípus, még nincs integrálva): @docs/voice-module.md

## Stack

- **backend/** — Java 25, Spring Boot 4.1.x, Maven. Postgres (pgvector), Flyway.
- **ai-service/voice/** — Python, önálló prototípus (faster-whisper, pyannote, Ollama).
  Még NEM kötött be a backendbe.
- **ai-service/docproc/** — Python (FastAPI) dokumentum-feldolgozó: PDF → labor /
  ambuláns adatok, Ollama-val. A Spring REST-en adja át, a Python callbackkel válaszol;
  a Spring sosem hívja az Ollamát. Ld. `docs/document-processing.md`.
- **frontend/** — React (Figma-terv alapján, még nincs kód).

## A rendszer központi elve: az Allitas (Statement)

Minden orvosnak mutatott állítás — laborjelölés, trend, gyógyszer-riasztás,
LLM-összefoglaló — egy `allitas` sorként létezik, sose nyers szövegként.
Három típus, és ez vizuálisan is megkülönböztetendő a frontenden:

- `szabaly` (DETERMINISTIC_RULE) — kód-alapú, reprodukálható, nincs bizonytalanság
- `forras` (RETRIEVED_FACT) — szó szerint a dokumentumból
- `modell` (MODEL_INFERENCE) — LLM-kimenet, KÖTELEZŐ bizonytalanság-jelzés

Minden `allitas` négy kérdésre válaszol: MI (szoveg), MERRE (forras —
karakter-offset vagy bbox vagy hang turn_id), MIKOR (klinikai_ido vs.
letrehozasi_ido — bitemporális), HOGY (eloallito: szabály-azonosító VAGY
modellnév+seed+hash). Ne adj ki állítást ezek nélkül.

Determinisztikus szabályt SOSE LLM-mel váltunk ki (pl. gyógyszer-interakció,
referencia-tartomány, TAJ-validáció) — a megbízhatóság ebből jön, nem a
modell méretéből.

## Adatmodell

Lásd: @docs/schema-decisions.md — de a legfontosabb, amit minden migrációnál
be kell tartani:

- **TAJ**: `VARCHAR(9)`, sose `INT` (vezető nulla). `CHECK (taj ~ '^[0-9]{9}$')`.
  Nullázható (SBO: azonosítatlan/külföldi beteg) — eldöntve, V2 migráció.
- Minden beteg-gyerektáblán idegen kulcs `pacient`-re, `ON DELETE CASCADE`,
  KIVÉVE a `hozzaferes_naplo`-t — az audit napló a beteg törlése után is
  megmarad.
- `dokumentum.kinyert_szoveg` kötelező, ha a dokumentumra bármilyen
  `allitas.forras` char-offsettel hivatkozik.
- Minden időbélyeg `TIMESTAMPTZ`, sose `TIMESTAMP` (nyári időszámítás).
- `labor.ertek_num` NUMERIC a motoroknak, sosem kerekítünk csendben. Cenzorált
  eredmény (`>90`, `<0,5`) V18 óta `value_num` = határ + `comparator` (`<`, `<=`,
  `>`, `>=`) — sosem pontos értékként. Kvalitatív eredmény (`Negatív`, `kevés`)
  V19 óta `value_text` + szöveges referencia `ref_text`; egy sorban pontosan az
  egyik: `value_num` VAGY `value_text`. A motoroknak a jelet és a szöveges sorokat
  is kezelniük kell (szöveges sort számként sosem értelmezünk).

## Flyway — szigorú szabály

**Alkalmazott migrációt SOSE szerkeszd.** A Flyway checksumot tárol; utólagos
módosítás a `flyway_schema_history`-t eltéríti, és az app nem indul. Minden
változtatás új `Vn__leiras.sql`. Fejlesztői DB-nél elakadás esetén
`docker compose down -v` az egyetlen elfogadható "reset".

`spring.jpa.hibernate.ddl-auto=validate` — a séma gazdája a Flyway, a
Hibernate csak ellenőriz.

## Java konvenciók, amiket eddig követtünk

- **Modul szerinti csomagolás, modulon belül réteg**: minden funkció
  (`patient`, később `recording`, `statement`, ...) saját mappa, abban
  `api/ persistence/ service/ exception/` almappák. A `GlobalExceptionHandler`
  és minden modulon átnyúló dolog `common/api`-ban van, NEM egy konkrét
  modulban.
- Entitás: sima osztály (nem record), `protected` no-arg konstruktor a
  JPA-nak, publikus konstruktor csak a ténylegesen megadható mezőkkel.
  `created_at`-szerű DB-generált mezőkre `@Generated` +
  `@Column(insertable=false, updatable=false)`.
- DTO-k (request/response): record. Entitást a controller SOSE ad vissza
  közvetlenül (jelszó-mező, lazy-init hiba, kliens nem küldhet id-t).
- Service: konstruktor-injektálás, `final` mező, nincs mezőre `@Autowired`.
- Kereséshez, ami érzékeny paramétert hordoz (TAJ, beteg neve stb.):
  **POST + request body**, SOSE `@RequestParam`/query string — a query
  string naplókban, proxykban leülepszik.
- Repository lekérdező metódusnév a Java-MEZŐnévre épül, nem az
  oszlopnévre (`findByBirthDate`, nem `findByBirth_date`).

## Amit tudatosan még nem oldottunk meg

- `patient`/`pacient` névinkonzisztencia (entitás angol, tábla félig magyar
  maradt) — ismert, egyelőre nem prioritás.
- `hozzajarulas` (GDPR beleegyezés) tábla még nincs migrálva.
- A hangmodul (voice/) teljesen külön fut, nincs Spring-integráció; a
  tervezett határ: a hangfolyam NEM megy át a Spring backenden, a Python
  szolgáltatás közvetlenül kommunikál a frontenddel, a Spring csak a
  validált végeredményt (megszollalas + allitas sorok) kapja meg.

## Amikor bizonytalan vagy egy döntésben

Ez egy tanulási projekt — a cél nem az, hogy helyetted írj kódot, hanem
hogy a döntés az övé maradjon. Ha egy változtatás architekturális hatással
jár (új tábla, séma-módosítás, csomagszerkezet), jelezd a trade-offokat,
mielőtt megírnád.
