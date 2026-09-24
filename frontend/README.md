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
nélkül). Más backend-címhez: `.env.example` → `.env.local`, `VITE_API_BASE`.

**Belépés (`/`, a kezdőoldal):** a többi oldal belépést kér. Itt lehet belépni
(`POST /api/auth/login`) vagy új orvos/admin profilt létrehozni
(`POST /api/users`, utána automatikus belépés). A belépett felhasználó
azonosítója megy minden kérés `X-User-Id` fejlécében (hozzáférési napló), és
ő a jóváhagyások `userId`-ja. AI-állítást csak `DOCTOR` hagyhat jóvá.

> **Még nem valódi védelem:** a backend nem ad munkamenetet/tokent, a
> végpontjai továbbra is elhiszik az `X-User-Id` fejlécet. A belépés a
> felületet zárja le, nem az API-t. A valódi megoldás a Spring Security.

## Szerkezet

```
src/
  api/
    dto.ts         a backend DTO-k PONTOS tükre (…/api/*Response.java)
    types.ts       nézet-típusok — ezt látják a komponensek
    index.ts       api-kliens + DTO → nézet leképezés (egyetlen helyen)
    http.ts        fetch-wrapper: X-User-Id fejléc, ProblemDetail-hibák
    currentUser.ts a belépett felhasználó (id, név, szerepkör)
  patient/
    PatientContext.tsx   az aktív beteg egyetlen igazságforrása
  components/
    allitas/       AllitasKartya, AllitasLista, ApprovalBar, jelzők
    shell.tsx      AppBar (felhasználó + kijelentkezés), PatientBand, PatientMatch, állapotok
    DocumentViewer.tsx   karaktertartomány-kiemelés + odagörgetés
    TranscriptView.tsx   orvos / beteg / hozzátartozó buborékok
    LabTrend.tsx         kis diagramok + táblázat nézet
    ChatPanel.tsx        klinikai asszisztens (még nincs végpont)
  pages/
    AuthPage.tsx         belépés + új profil (orvos / admin)
    PatientListPage.tsx  /patients — keresés TAJ vagy név+anyja neve+szül. dátum
    PatientPage.tsx      négyoszlopos munkaterület, összecsukható panelek
```

## Bekötött végpontok

| Nézet | Végpont |
|---|---|
| Belépés / új profil | `POST /auth/login`, `POST /users`; jóváhagyó neve: `GET /users` |
| Beteglista / keresés | `GET /patients`, `POST /patients/search` (törzsben — TAJ nem mehet URL-be) |
| Új beteg | `POST /patients` (TAJ nélkül is: „Nincs TAJ” jelölővel) |
| Beteg | `GET /patients/{id}` |
| Állítások | `GET /statements?patientId=`, `POST /statements/{id}/approvals` |
| Dokumentumok | `GET /documents?patientId=`, `GET /documents/{id}`, `…/text`, `…/file` |
| Hangfelvétel | `GET /recordings?patientId=`, `GET /recordings/{id}/utterances` |
| Labor-idősor | `GET /lab-results?patientId=` (név + mértékegység szerint csoportosítva) |
| Klinikai asszisztens | nincs végpont — a panel ezt jelzi |

## Biztonsági invariánsok a kódban

- **Beteg-azonosítás:** a `PatientPage` minden betegfüggő adatot egy
  lépésben tölt be, és ha bármelyik elem `patientId`-ja eltér, a nézet nem
  jelenik meg. A dokumentum-, átirat-, labor- és chat-nézet külön is
  összeveti a saját `patientId`-ját az aktív beteggel, és eltérésnél elrejti
  a tartalmat. Betegváltáskor a route `key`-je miatt minden állapot
  (a chat-előzmény is) törlődik.
- **Állítástípusok:** ikon + felirat + szegélystílus, nem csak szín.
  `szabaly`-n nincs jóváhagyás-gomb és bizonytalanság; `modell`-en a
  bizonytalanság és az `ApprovalBar` kötelező.
- **Kritikus állítás** nem csukható össze, és szűréskor is látszik.
- **Forrás-hivatkozás:** a dokumentum-tartomány Unicode code pointban értendő
  (mint a backendben), a hangforrás `turnIds`-e a felvétel turn-sorszámai
  (nem megszólalás-azonosítók).

## Nyitott kérdések

1. **Chat** — nincs végpont. A „Mentés állításként” gomb letiltva, amíg el
   nem dől, lehet-e chatválaszból `modell`-állítás.
2. **Hitelesítés** — a belépés ellenőrzi a jelszót, de a backend nem ad
   munkamenetet: az `X-User-Id` bárki által beírható. Admin profilt is bárki
   létrehozhat a nyilvános regisztrációval.
3. **Képforrás (IMAGE)** — a hivatkozás a dokumentumot nyitja meg, a
   kijelölt terület (bbox) kiemelése még nincs megrajzolva.
4. **Laborsor → lelet** — a laborsornak nincs karaktertartománya a leleten
   belül, ezért a pont a lelet egészét nyitja meg.
