# MediAI Assistant — frontend

React 19 + TypeScript + Vite, react-router. Nincs UI-könyvtár: a vizuális
identitás a `src/styles/tokens.css` tokenjeiből jön.

## Indítás

A frontend a Spring Boot backendre épül — demó adat nincs, előbb a backend
(és a Postgres) fusson:

```bash
docker compose up -d            # a repó gyökerében: Postgres
cd backend && ./mvnw spring-boot:run   # http://localhost:8080
cd frontend && npm install && npm run dev   # http://localhost:5173
```

Dev módban a `/api` kéréseket a Vite a `localhost:8080`-ra proxyzza (CORS
nélkül). Másik backend-példányhoz (pl. teszt-adatbázissal):
`MEDIAI_PROXY_TARGET=http://localhost:8081 npm run dev`. Éles buildnél az
alap-URL: `.env.example` → `.env.local`, `VITE_API_BASE`.

**Belépés (`/`, a kezdőoldal):** a többi oldal belépést kér. Itt lehet belépni
(`POST /api/auth/login`) vagy új orvos/admin profilt létrehozni
(`POST /api/users`, utána automatikus belépés). A belépett felhasználó
azonosítója megy minden kérés `X-User-Id` fejlécében (hozzáférési napló), és
ő a jóváhagyások, ellátások, felvételek `userId`-ja. Induláskor a tárolt
belépést a backend ellenőrzi (`GET /api/users/{id}`): adatbázis-visszaállítás
után a nem létező vagy más felhasználóra mutató belépés kiléptet.

> **Még nem valódi védelem:** a backend nem ad munkamenetet/tokent, a
> végpontjai továbbra is elhiszik az `X-User-Id` fejlécet. A belépés és az
> admin-oldal jogosultsága a felületet zárja le, nem az API-t. A valódi
> megoldás a Spring Security.

## Oldalak

| Útvonal | Tartalom |
|---|---|
| `/` | belépés + új profil (orvos / admin) |
| `/patients` | beteglista, keresés (TAJ vagy név + anyja neve + szül. dátum), új beteg |
| `/betegek/:id` | betegoldal — fülek: Dokumentum, Labor-idősor, Átirat, Ellátások, Gyógyszer és allergia, Napló; mellette az állítások és a chat |
| `/profil` | saját adatok, hangprofil (aktív, előzmény, új), saját hozzáférési napló |
| `/admin` | csak adminnak: felhasználók szerepkör szerint, keresés névre/emailre, kiválasztott felhasználó hangprofilja és naplója |

## Szerkezet

```
src/
  api/
    dto.ts         a backend DTO-k PONTOS tükre (…/api/*Request.java, *Response.java)
    types.ts       nézet-típusok — ezt látják a komponensek
    mappers.ts     DTO ↔ nézet leképezés (egyetlen helyen)
    importok.ts    gépi formátumok ellenőrzése (Python átirat, RAG-darabok)
    index.ts       az api-kliens: mind a 46 végpont
    http.ts        fetch-wrapper: X-User-Id fejléc, ProblemDetail-hibák, multipart, blob
    currentUser.ts a belépett felhasználó (id, név, szerepkör)
  patient/
    PatientContext.tsx   az aktív beteg egyetlen igazságforrása + csendes frissítés
  components/
    allitas/       kártya, lista, jóváhagyás (+ előzmény, új döntés), idézet
    DokumentumFul / DocumentViewer / DokumentumReszek   feltöltés, szöveg, kijelölés → idézet, RAG
    AtiratFul / TranscriptView   új felvétel, állapotgép, átirat-import, idézet a megszólalásból
    LabTrend.tsx         diagram + táblázat, szűrés, mérés részletei, panel rögzítése
    EllatasPanel / GyogyszerAllergiaPanel / BetegNaplo / HangprofilPanel / NaploTabla
    ErrorBoundary.tsx    renderelési hibánál hibaállapot, nem fehér oldal
  lib/
    useAsync.ts    betöltés (reload) + csendes frissítés (refresh)
    useAkcio.ts    egy művelet (mentés, lezárás) folyamatban/hiba állapota
    kijeles.ts     szövegkijelölés → Unicode code-point tartomány
    format.ts      dátum (helyi idő), szám (magyar), méret
  pages/
    AuthPage, PatientListPage, PatientPage, ProfilePage, AdminPage
```

## Bekötött végpontok (mind a 46)

| Terület | Végpontok → felület |
|---|---|
| Belépés, felhasználó | `POST /auth/login`, `POST /users` (belépés/új profil) · `GET /users/{id}` (munkamenet-ellenőrzés, nevek) · `GET /users?role=`, `POST /users/search` (admin) |
| Hangprofil | `GET /voice-profiles/{u}/active`, `…/history`, `POST /voice-profiles` (profil, admin) |
| Beteg | `GET /patients`, `POST /patients/search`, `POST /patients`, `GET /patients/{id}` |
| Ellátás | `GET /medical-cares?patientId=`, `GET /medical-cares/{id}`, `POST /medical-cares`, `POST …/{id}/close` |
| Felvétel, átirat | `GET /recordings?patientId=`, `GET /recordings/{id}`, `POST /recordings`, `PATCH …/{id}/status`, `GET`/`POST /recordings/{id}/utterances` |
| Dokumentum | `GET /documents?patientId=`, `GET /documents/{id}`, `POST /documents` (multipart; PDF típus nélkül → háttérben AI-feldolgozás, a lista 5 mp-enként frissül, amíg tart), `GET …/file`, `GET`/`PUT …/text`, `GET`/`POST …/chunks` |
| Állítás | `GET /statements?patientId=`, `GET /statements/{id}`, `POST /statements` (kijelölt idézet), `GET`/`POST …/{id}/approvals` |
| Labor | `GET /lab-results?patientId=` (`&name=`), `GET /lab-results/{id}`, `POST /lab-results` |
| Gyógyszer, allergia | `GET /medications?patientId=&activeOnly=`, `GET /medications/{id}`, `POST /medications`, `POST …/{id}/discontinue` · `GET /allergies?patientId=`, `GET /allergies/{id}`, `POST /allergies` |
| Napló | `GET /access-logs?patientId=` (beteg Napló fül), `GET /access-logs?userId=` (profil, admin) |
| Klinikai asszisztens | nincs végpont — a panel ezt jelzi |

**Gépi formátumok** (a felületről importálhatók, a kliens előbb ellenőrzi):
átirat — a Python `beszed_pipeline.py` kimenete (`chat`: `orvos/beteg/egyéb`
→ `DOCTOR/PATIENT/OTHER`, másodperc → ms) vagy a backend `utterances` tömbje;
RAG-darabok — `{ embeddingModel, chunks: [{ charStart, charEnd, embedding[1024] }] }`.
Állítás csak kézzel, kijelölt idézetként hozható létre a felületről (`RETRIEVED_FACT`).

## Biztonsági invariánsok a kódban

- **Beteg-azonosítás:** a `PatientPage` minden betegfüggő adatot egy
  lépésben tölt be, és ha bármelyik elem `patientId`-ja eltér, a nézet nem
  jelenik meg. Minden panel (dokumentum, átirat, labor, ellátás, gyógyszer,
  allergia, napló, részlet-dobozok) külön is összeveti a saját `patientId`-ját
  az aktív beteggel, és eltérésnél elrejti a tartalmat. URL-ből csak az aktív
  beteg dokumentuma/felvétele nyitható. Betegváltáskor a route `key`-je miatt
  minden állapot törlődik.
- **Állítástípusok:** ikon + felirat + szegélystílus, nem csak szín.
  `szabaly`-n nincs döntés-sáv; `modell`-en a bizonytalanság és a jóváhagyás
  kötelező; `forras` megerősíthető — gyógyszer/allergia forrása csak
  jóváhagyott állítás lehet. Döntés után az érvényes állapotot a backend adja
  (`GET /statements/{id}`), a kliens nem számolja.
- **Kritikus állítás** nem csukható össze, és szűréskor is látszik.
- **Forrás-hivatkozás:** a dokumentum-tartomány Unicode code pointban értendő
  (mint a backendben) — a kijelölés is így számol, emoji előtte sem csúsztatja
  el; a hangforrás `turnIds`-e a felvétel turn-sorszámai.
- **Nincs csendes kerekítés:** a labor- és dózisbevitel a backend
  tizedes-korlátjához igazodik, cenzorált/kvalitatív érték (`<0,5`,
  `negatív`) nem rögzíthető; a megjelenítés is annyi tizedest mutat, amennyi
  az értékben van.
- **Fájlletöltés** fetch-csel, `X-User-Id` fejléccel — a naplóban látszik, ki
  vitte ki (EXPORT).

## Nyitott kérdések

1. **Chat** — nincs végpont. A „Mentés állításként” gomb letiltva, amíg el
   nem dől, lehet-e chatválaszból `modell`-állítás.
2. **Hitelesítés** — a belépés ellenőrzi a jelszót, de a backend nem ad
   munkamenetet: az `X-User-Id` bárki által beírható, és az admin-jogosultságot
   csak a felület tartja be. Admin profilt is bárki létrehozhat.
3. **Képforrás (IMAGE)** — a hivatkozás a dokumentumot nyitja meg, a
   kijelölt terület (bbox) kiemelése még nincs megrajzolva; kézzel képforrású
   idézet nem rögzíthető.
4. **Laborsor → lelet** — a laborsornak nincs karaktertartománya a leleten
   belül, ezért a lelet egésze nyílik meg.
5. **Visszavont forrás** — ha egy gyógyszer/allergia forrás-állítását utóbb
   elutasítják, a tétel a listán marad (a backend nem vonja vissza); a felület
   figyelmeztetést mutat mellette.
