# Mérőkörnyezet (IDK C. melléklet)

Megismételhető mérések szintetikus adaton. A nyers kimenet és a jelentés a `results/` mappába kerül
(időbélyeges fájlnév); a jelentés a nyers fájlból újraszámolható.

| Szkript | Mit mér | Futtatás |
|---|---|---|
| `extraction_eval.py` | a hangmodul prototípusának tünetkinyerése (`extract()`), 5 kitalált SBO-párbeszéd × N futás, modellenként (C1, M8) | `..\voice\ai_env\Scripts\python extraction_eval.py medgemma:latest qwen3.5:9b medgemma:27b --runs 3` |
| `extraction_eval.py --rescore F.json` | egy korábbi nyers fájl (a régi `benchmark_*.json` is) újrapontozása modellhívás nélkül | `python extraction_eval.py --rescore ..\voice\benchmark_20260916_153024.json` |
| `literature_eval.py` | Szakirodalom: kifejezés-javaslat, keresési idő, AI-rangsor, cikkértékelés 3 kitalált betegen (C2, M6) | `python literature_eval.py --search-reps 3` |
| `literature_eval.py --score-labels F.csv` | a kézzel kitöltött relevancia-címkék és a modell egyezése (pontos, súlyozott kappa) | |
| `lab_eval.py` | PDF-laborkinyerés 3 szintetikus leleten (a szkript készíti, az aranystandard a forrás), soronkénti felidézés / precizitás, mezők, PDF-kiemelés (2. mérés, M4) | `..\docproc\.venv\Scripts\python lab_eval.py --model medgemma:latest` |
| `findings_eval.py` | a felvétel hat csoportja, 3 kitalált SBO-párbeszéd, 39 elvárt elem (8. mérés) | `..\voice\ai_env\Scripts\python findings_eval.py medgemma:latest medgemma:27b` |
| `review_eval.py` | hangátnézés kitalált átiraton, beültetett szóhibák, beszélőcsere, lehetetlen echo-értékek (4. mérés) | `..\voice\ai_env\Scripts\python review_eval.py medgemma:latest medgemma:27b` |
| `history_eval.py` | kórtörténet-kivonatok 4 kitalált dokumentumon: diagnózis, BNO, korábbi esemény (7. mérés) | `..\docproc\.venv\Scripts\python history_eval.py medgemma:latest medgemma:27b` |
| `clinical_eval.py` | klinikai asszisztens (4 kérdés) és összefoglaló egy kitalált betegen (3. mérés; a beteg: `synthetic_patient.py`) | `..\docproc\.venv\Scripts\python clinical_eval.py medgemma:latest qwen3.5:9b medgemma:27b` |
| `ddi_coverage.sql` | gyógyszer-interakciós referenciaadat lefedettsége + ismert párok (9. mérés) | `docker exec -i mediai-assistent-db-1 psql -U postgres -d mydatabase < ddi_coverage.sql` |
| `run_all.sh` | a 2., 3., 4., 7., 8. mérés egymás után (≈2 óra a 27B miatt) | `sh run_all.sh` |
| `backend/…/research/service/OutboundQueryGateEvalTest.java` | a kimenő kapu tesztvektorai (M1) egy kitalált betegen | `backend\mvnw -q test -Dtest=OutboundQueryGateEvalTest` |

## A kinyerés-kiértékelés (a 2026-09-16-i `llm_teszt.py`-hoz képest)

Az aranystandard ugyanaz (a `voice/llm_teszt.py` `CASES`-e, onnan olvasva). A pontozás változott:

1. **Üres / érvénytelen kimenet = sikertelen futás.** A régi kiértékelő 0 pontot adott, de a „Sikertelen”
   oszlopban nem számolta: a 2026-09-16-i nyers fájlban a MedGemma 4B a mellkas és a hasi esetben mindhárom
   futásban üres JSON-t adott (60/174 pont).
2. **Precizitás**: minden kinyert tünet / gyógyszer / allergia vagy egy elvárt elemnek felel meg, vagy többlet;
   a többlet „elhangzott” vagy „nem hangzott el” (kézi átnézésre listázva).
3. **Szótő csak szó elején** illeszkedik (`kar` ≠ `akar`); gyógyszer- és allergianév ékezettől függetlenül
   (`ibuprofén` = `ibuprofen`). Bővítés: `fejfájás` ← „fáj a feje” (`ALIASES`).
4. **Téves tagadás** a jelen lévő tünetre is, nem csak a meg nem válaszolt kérdésre.
5. **Forrás** függetlenül ellenőrizve: a hivatkozott megszólalásban vagy az előtte álló kérdésben ott van-e az
   elvárt elem (nem a pipeline saját figyelmeztetéseinek száma).
6. **Névtorzítás** (`atorvasztantin`) hiányzó elemnek számít; gyógyszer ↔ allergia felcserélése súlyos hiba.
7. **Idő**: medián az első (modellbetöltő) hívás nélkül; a 27B-hez a határidő 900 s (`LLM_DEADLINE`).

A `qwen3.5:9b`-t gondolkodás nélkül hívja (`think: false`, mint a termék docproc-ja); a prototípus
`_call_ollama`-ja ezt nem küldi, ezért a szkript a hívást kiegészíti.

## Korlátok

Öt kitalált párbeszéd, egy szerző aranystandardja; nem klinikus validálta. A Szakirodalom relevancia-helyessége
csak kézi címkével mérhető (a CSV-t klinikus töltse ki); az „előszűrés = értékelés” a modell belső egyezése.
A keresési idő hálózatfüggő. Azonos kimenet csak azonos modellváltozat, Ollama-verzió és hardver mellett várható.
