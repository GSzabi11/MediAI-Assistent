# Ellátás-kontextus és Informálódás mód

Szabi döntése (2026-10-24): a beteg kiválasztása után az orvosnak rögtön döntenie kell, **milyen keretben**
nyitja meg a beteget — egy nyitott ellátásban dolgozik, vagy csak informálódik. Migráció nem kellett: az
ellátás (`medical_care`, V8) és a felvétel → ellátás kötés (`recording.medical_care_id`) már létezett.

## A választás (frontend)

- A betegoldal (`pages/PatientPage.tsx`) munkaterülete csak választás után jelenik meg; addig az
  **ellátás-választó** (`components/EllatasValaszto.tsx`) látszik:
  - a beteg **nyitott** ellátásai (több is lehet, pl. SBO alatt echo — ezért a választás mindig explicit);
    az utoljára használt nyitott ellátás előválasztva (fókuszban, Enterrel folytatható);
  - **új ellátás** egy kattintással (`SBO | AMBULÁNS | ECHO`; az utoljára nyitott típus kiemelve);
  - **Informálódás (csak olvasás)**.
- **Egyetlen igazságforrás**: `patient/EllatasKontextus.tsx` (React context, `useEllatasKontextus()`), és
  az URL: `?ellatas=<id>` vagy `?mod=informalodas` — frissítés és új lap után is megmarad. A betegoldalon
  belüli linkek többsége csak `?nezet=…`-t ad meg; ha a paraméter kiesik, a kontextus visszaírja (replace).
- Csak a beteg **nyitott** ellátása lehet aktív: lezárt, ismeretlen vagy más betegé → vissza a választóhoz,
  üzenettel. Az „utoljára használt” betegenként a böngészőben (`localStorage`) — csak kényelmi.
- **Fejléc-chip** a betegsávban: „Név · TÍPUS · nyitva X ó” vagy „Informálódás · csak olvasás”;
  kattintásra vissza a választóhoz.

## Mi kötődik az ellátáshoz

- **Hangfelvétel**: a `medicalCareId` automatikusan az aktív ellátás (nincs külön választás); a gomb mutatja
  a célt („Mentés: SBO-ellátásba (#12)”). Ha az ellátást közben lezárták, a backend 409-et ad (eddig is így
  volt) — a frontend lekéri az ellátást, és ha lezárult, visszavisz a választóhoz.
- **Az aktív ellátás lezárása** az Ellátások fülön → vissza a választóhoz.
- **Epikrízis**: csak az aktív ellátáshoz készíthető / szerkeszthető; a többi ellátásé (és Informálódás
  módban mindegyik) csak olvasható, exportálható. A leletek kiválasztása változatlan (klinikai dátum az
  ellátás időszakában, `docs/epicrisis.md`).
- **PDF-dokumentum NEM kötődik ellátáshoz** (később az EESZT-ből jön) — nincs `document.medical_care_id`.
  Feltölteni ellátás-módban és — **ideiglenesen** — Informálódás módban is lehet (ld. lent).

## Informálódás mód = csak olvasás

Minden olvasható. **Továbbra is működik**: Klinikai asszisztens (chat), Klinikai státusz (készítés /
újragenerálás), ad hoc gyógyszer-interakció ellenőrzés, Medical history (készítés, megtekintés, DOCX-export),
Szakirodalmi kutatás (keresés, rangsor, cikk-jelölés is — a kutatás nem a beteg dokumentációja), és
**ideiglenesen a dokumentum (PDF) feltöltése** (Szabi döntése, 2026-10-24): amíg nincs automatikus
EESZT-import, csak így vihetők be régi leletek anélkül, hogy egy új ellátáshoz kellene kötni őket. Az
EESZT-import után visszakerül a tiltottak közé.

**Tiltva**: felvétel / átirat-import, a dokumentum többi írása (újrafeldolgozás, kézi szöveg, RAG-darabok), minden hozzáadás / szerkesztés /
törlés (gyógyszer, allergia, labor, állítás-jóváhagyás és idézet, kulcsszó, felvétel-összefoglaló és
-csoportok, Medical history kézi szerkesztése, epikrízis), ellátás nyitása / zárása, interakciós adatbázis
frissítése. A tiltott gombok **látszanak, de le vannak tiltva** („Informálódás módban nem elérhető”
tooltippel); a munkaterület fölött halvány „Csak olvasás” sáv, „Megnyitás ellátásban” gombbal (vissza a
választóhoz). A frontenden az `useIrhato()` hook adja a `disabled` + `title` párt.

## Szerver-oldali kényszerítés

Informálódás módban a frontend minden kéréshez `X-Access-Mode: READ_ONLY` fejlécet tesz (`api/http.ts`,
`setCsakOlvasas`; a betegoldal elhagyásakor kikapcsol). A `common/api/ReadOnlyModeInterceptor` ilyenkor
minden nem `GET/HEAD/OPTIONS` `/api/**` hívást **403**-mal elutasít (ProblemDetail a
`GlobalExceptionHandler`-ből, `ReadOnlyModeException`), kivéve az engedélylistát:

| Metódus | Út |
|---|---|
| POST | `/api/auth/login`, `/api/{…}/search` (beteg, felhasználó) |
| POST | `/api/chat-messages` |
| POST | `/api/clinical-summaries` |
| POST | `/api/patients/{id}/drug-interactions/check` |
| POST | `/api/medical-histories` (az export `GET …/file`) |
| POST | `/api/documents` (multipart feltöltés) — **ideiglenes, amíg nincs EESZT-import** |
| POST / PUT / PATCH | `/api/research-contexts`, `/api/research-contexts/{id}/edited-text`, `/api/research`, `/api/research/{id}/ranking`, `/api/research/{id}/evaluations`, `/api/research/{id}/articles/{aid}` |

A Python callbackjei nem küldik a fejlécet, rájuk nem hat. Az interceptor a hozzáférési napló után fut,
így az elutasított író kísérlet is naplózódik (403-mal).

**Őszintén: ez csak a tévedés ellen véd.** Bejelentkezés nincs, az `X-User-Id` is bármire átírható; a
fejlécet a kliens küldi, elhagyásával a tiltás megkerülhető. Valódi védelem a munkamenethez kötött mód
(szerver-oldali állapot) lenne, az autentikáció bevezetése után.

## Ismert rések

- A **mód nem kerül a hozzáférési naplóba**: az `access_log`-nak nincs rá oszlopa, migráció kellene (V36).
  A 403-as sorok látszanak, de az Informálódás módban végzett olvasás nem különböztethető meg.
- Több lapon különböző mód lehet ugyanannál a betegnél — szándékos (a mód lapé, az URL-ben él).
- A régi, ellátás nélküli felvételek (`medical_care_id = NULL`) maradnak; újat ellátás nélkül a felület már
  nem készít (a JSON-os kézi import API-ja továbbra is engedi).
