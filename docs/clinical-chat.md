# Klinikai asszisztens (RAG-chat) — V24, V25

A betegoldal jobb oldali panelje. Az orvos kérdez a betegről; a válasz a beteg **összes
dokumentumából** (visszakeresett szövegrészek) és a strukturált adataiból (labor, kinyert
állítások, gyógyszer, allergia) készül, és **minden pontja a forrására hivatkozik** — a chip a
dokumentumot a hivatkozott karakter-tartománnyal, ill. a laborsort nyitja meg.

## Tudatos döntés: nem `statement`, és nem is tárolódik (V25)

A felhasználó döntése: a válasz **nem** állítás (ugyanaz a kivétel, mint a
`clinical_summary`-nél), a pontok nem jóváhagyhatók, és nem kerülnek az állításlistába.

**V25 óta a beszélgetés nem tárolódik** (a V24 `chat_message` táblát a V25 törölte, a
sorokkal együtt): újratöltés után, ill. más felhasználónak a chat üres. Az üzenet a Spring
memóriájában él (`ChatMessageStore`) — a válasz elkészüléséig, a felület lekérdezéséig és a
következő kérdés előzményéig kell —, a kérdéstől számított 2 óráig, utána törlődik; újraindításkor
elvész. Az azonosító az indulás idejéből (ms) indul, így újraindítás után egy elkésett Python-callback
nem írhat egy új kérdésbe (404). Memóriában a válasz mellett ugyanazt tartjuk, mint korábban:

- **MIBŐL**: `context` (a hivatkozható strukturált tények) + `retrieved` (a kérdéshez
  visszakeresett `document_chunk`-ok) — `{kind, id, label, source}` elemek;
- **HOGY**: `modelName`, `seed`, `promptHash`, `extractorVersion`, `embeddingModel`;
- **MIKOR**: `requestedAt`, `completedAt`; `history` — a modellnek átadott korábbi kérdések.

**Ára**: utólag nem igazolható, mit mondott a modell egy adott kérdésre. A hozzáférési napló
továbbra is rögzíti a chat-hívásokat a beteggel (a controller kérés-attribútumban adja át, mert
nincs tábla, amiből a `PatientResolver` feloldaná), de a kérdés és a válasz szövegét nem.

A felület kiírja: „AI-válasz döntéstámogatásra — nem jóváhagyott állítás”.

## Folyamat

```
POST /api/chat-messages {patientId, userId, question, previousMessageIds≤3}   → 202, PROCESSING
  Spring: ChatContextBuilder — a beteg teljes előzménye strukturáltan (név/TAJ/lakcím nélkül)
  commit után @Async → POST {ai-service}/chats      (a sor ELEJÉRE; a futó feladatot megvárja)
Python (worker, a GPU-sorban):
  1. a kérdés BGE-M3 vektora (visszautaló kérdésnél az előző kérdéssel együtt)
  2. POST /api/chat-messages/{id}/retrieve {embedding}  → a beteg 8 legközelebbi darabja (pgvector)
  3. a modell számozott forrásokat kap, [n] jelekkel hivatkozik
  4. determinisztikus utófeldolgozás → POST /api/chat-messages/{id}/result
GET /api/chat-messages/{id}         — a készülő válasz állapota (a felület 1,5 mp-enként kérdezi)
```

Listázó végpont nincs (V25 óta): a felület nem tölt vissza előzményt. A lejárt előzmény-azonosító
(`previousMessageIds`) csendben kimarad. Betegenként egyszerre egy készülő válasz (a
`ChatMessageStore` szinkronizáltan ellenőrzi, 409). A kérdés a kérés
törzsében megy, sosem query stringben (CLAUDE.md: érzékeny adat).

**Miért a Spring keres, nem a Python?** Az adatbázist csak a Spring éri el (architektúra-határ);
a vektort viszont a Python tudja kiszámolni (ott a BGE-M3). Ezért a Python a feldolgozás közben
egyszer visszahív a Springbe a kérdés vektorával.

## A modell bemenete

- **Dokumentum-darabok** (8, a legközelebbi elöl). A HNSW-index helyett a beteg darabjain
  **pontos** keresés fut (MATERIALIZED CTE): az index a beteg-szűrést a keresés után végezné, és
  sok beteg mellett egy beteg darabjai csendben kimaradhatnának.
  **Sablonszűrés**: ha egy darab egy másik, újabb dokumentumban is szinte szó szerint megvan
  (koszinusz-távolság < 0,05 — EESZT-lábléc, aláírás-blokk), csak a legújabb példány marad.
- **Labor** vizsgálatonként (egységes név + mértékegység): a legutóbbi érték referenciával, a
  szabálymotor minősítésével (`FELETT/ALATT/ELTÉR`), és a 3 korábbi érték. A **tartományon
  kívüliek mindig** mennek; a normálok csak akkor részletesen, ha a kérdés említi őket (vagy a
  „labor” szót) — egyébként csak a nevük, háttérként. Oka: a 80+ soros lista a kis modellnél
  elnyomta a kérdéshez tartozó részt.
- **Kinyert állítások** (a szabály-állítások és az elutasítottak nélkül), a forrásukkal.
- **Gyógyszer, allergia**: a forrás-állításuk forrásával hivatkozható; kézi rögzítés csak háttér.
- **Háttér** (nem hivatkozható): életkor, a dokumentumok listája.

## Ellenőrzés (determinisztikus, a modell után)

- A `[n]` jelek → `{kind, id}`; **nem létező sorszám** elhagyva, figyelmeztetéssel.
- A Spring **csak a modellnek átadott tényekre** enged hivatkozni (`context ∪ retrieved`) —
  máshová mutató forrás → 400, a kérdés FAILED. Így a chip sosem mutathat más beteg adatára.
- **Ellenőrizetlen szám** (tizedes vagy ≥ 10, ami a bemenetben nem szerepel) → figyelmeztetés.
- **Hurok**: streamelve figyelve, egy legalább 4 szavas **sor/mondat** szó szerinti ismétlődésénél
  leáll (a tagmondat-szintű figyelés a felsorolásoknál hamisan jelzett: a dátum pontjai a
  „magas a referencia felett (2026” részt minden pontban azonos tagmondattá törték).
- **Bizonytalanság** (`LOW | MEDIUM | HIGH`) a **forrás-lefedettségből**, nem a modell
  önértékeléséből: minden tartalmi pontnak van forrása és nincs figyelmeztetés → LOW; legfeljebb
  a felük forrás nélküli → MEDIUM; különben HIGH. A bevezető (kettősponttal záruló) mondatnak nem
  kell forrás.
- A hivatkozott laborsorok **szabály-állításai** (`lab-reference-range`) külön, lakatos
  hivatkozásként jelennek meg (olvasáskor számolva — ha a szabály azóta visszavonta, eltűnik).

## Modellválasztás — mérés (RTX 5060 8 GB, 9-es tesztbeteg, 8 dokumentum)

Ugyanaz a prompt („Mik a kóros laboreredmények és hogyan alakultak?”):

| Modell | Eredmény |
|---|---|
| `medgemma:latest` (4B) | hurokba esett (ugyanaz a sor 15×), kitalált érték (Kreatinin 80), a hivatkozások dokumentum-darabokra mutattak a laborsor helyett |
| `gemma4:e4b` | „nincs információ” (gondolkodás nélkül) |
| `qwen3.5:9b` | 8 pont, mind a helyes laborsorra hivatkozik, LOW bizonytalanság; ~55–85 s |

Ezért a chat modellje külön állítható (`CHAT_LLM_MODEL`, alapból = `DOC_LLM_MODEL`); a
dokumentum-feldolgozás maradhat MedGemmán. A qwen3.5 gondolkodó modell: a hívás `think: false`-t
küld (a nem gondolkodó modellek ezt figyelmen kívül hagyják).

## Ismert korlátok

- A kis modell a „legutóbbi” szót nem mindig tartja be (régebbi lap tartalmát is összefoglalja) —
  a chipeken a dátum látszik, így ellenőrizhető.
- A darab-hivatkozás a teljes ~800 karakteres darabra mutat, nem a pontos mondatra.
- Hangfelvétel (megszólalások) még nincs a kontextusban — csak a belőlük kinyert állítások.
- A javaslatok orvosszakmai helyességét semmi nem ellenőrzi — feltételes mód, „döntés az orvosé”.
- „Új beszélgetés”: a felület kiüríti a beszélgetést (a modell a korábbiakat nem látja többé).
- Ha a felhasználó újratölt, mialatt egy válasz készül, a válasz nem jelenik meg, és a
  feldolgozás végéig az új kérdés 409-et kap.
