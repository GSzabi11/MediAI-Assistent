# Adatmodell-döntések

A teljes ER-diagram a repóban: `docs/er-diagram.png` (töltsd be ide a
legutóbbi drawio-exportot). Ez a fájl a döntések *miértjét* rögzíti, amit
egy diagram nem mutat.

## Táblalista (célállapot — nem mind létezik még migrációban)

`felhasznalo`, `hangprofil`, `paciens`, `ellatas`, `hozzajarulas`,
`dokumentum`, `labor`, `felvetel`, `megszolalas`, `allitas`,
`allitas_jovahagyas`, `hozzaferes_naplo`, később: `gyogyszer`, `allergia`,
`doc_chunk`, `iranyelv_chunk`.

Jelenleg migrálva: `pacient`, `app_user` (= a diagram `felhasznalo`-ja),
`voice_profile` (= `hangprofil`), `recording` (= `felvetel`),
`utterance` (= `megszolalas`).

## `app_user` (felhasznalo)

Angol elnevezés, a tábla neve `app_user`, mert a `user` Postgresben
foglalt szó. Jelszó sosem tárolódik nyersen: `password_hash` (BCrypt, a
`spring-security-crypto` modulból — a teljes Spring Security még nincs
bekötve). A szerepkör `DOCTOR | ADMIN`, CHECK-kel kikényszerítve.

## `voice_profile` (hangprofil)

Felhasználónként több sor (a Python `--uj` a régit `.bak`-ként megtartja),
de legfeljebb egy `active = true` — részleges egyedi indexszel
(`WHERE active = true`) kikényszerítve, nem csak kódban. Létrehozáskor a
service a régi aktívat inaktiválja és **flush-ol** a beszúrás előtt (IDENTITY
miatt az INSERT azonnal fut, a dirty-check UPDATE csak commitkor).

Az `embedding vector(256)` (wespeaker-resnet34, float32) oszlop létezik, de
a JPA-entitás szándékosan NEM képezi le: egyelőre a Python hangmodul
kezeli a vektort, a Spring csak metaadatot. Ha Java-oldalon kell, a
`hibernate-vector` + `float[]` + `@JdbcTypeCode(SqlTypes.VECTOR)` az út.
`threshold` nullázható — a Python tesztenként adja meg, nem a profilban.
`ON DELETE CASCADE` az `app_user`-re: a hanglenyomat biometrikus adat,
a fiókkal együtt törlendő.

## `recording` (felvetel)

- **Törlési szabályok**: `patient_id` → `ON DELETE CASCADE` (beteg-gyerektábla);
  `user_id` → `RESTRICT` (orvos nem törölhető, amíg felvételei vannak — a
  betegdokumentáció nem tűnhet el vele); `voice_profile_id` → `SET NULL`
  (a biometrikus profil törölhető, a felvétel megmarad).
- **`encounter_id` FK nélkül**, nullázható — az `ellatas`/encounter tábla
  még nem létezik; az FK a vele együtt érkező migrációban kerül rá.
  Addig az érték nincs ellenőrizve.
- **`voice_profile_id` nem a klienstől jön**: létrehozáskor a service az
  orvos akkor aktív profilját írja be (vagy NULL).
- **Státusz-állapotgép** (`RecordingStatus.canTransitionTo`, az entitás
  kényszeríti ki): `RECORDING → PROCESSING | FAILED`,
  `PROCESSING → DONE | FAILED`, `DONE`/`FAILED` végállapot. Tiltott
  átmenet → 409. `duration_sec` a `PROCESSING`-re lépéskor kötelező.
- **`recorded_at` = a felvétel létrejöttének pillanata**, a DB adja
  (`default now()`, V6), a kliens nem küldheti; nincs külön `created_at`.
  Entitáson `@Generated` + `insertable=false` — Java-oldalon NEM állítjuk
  (a kettő keverése miatt a V5-ös sorok NULL-t kaptak).
- `asr_model` NOT NULL: a voice-module.md szerint az `eloallito`-ban az
  ASR-modellt is rögzíteni kell, ez a forrása.

## `utterance` (megszolalas)

- **Megváltoztathatatlan.** Az `allitas.forras` `(recording_id, turn_id)`
  párral hivatkozik rá (`UNIQUE`), egy utólagos módosítás a provenance-t
  hamisítaná — ezért nincs update/delete végpont és módosító metódus.
- **Egyben, egyszer töltődik be**: `POST /api/recordings/{id}/utterances`
  a teljes listával, egy tranzakcióban, csak `PROCESSING` felvételhez;
  második próbálkozás → 409. (voice-module.md határ: a Spring a kész
  eredményt kapja, nem élő átiratot.)
- `speaker`: `DOCTOR | PATIENT | OTHER` — a Python `orvos | beteg | egyéb`
  kimenetének 1:1 megfelelője. Az `OTHER` a hozzátartozó / harmadik fél.
  **Tudatos döntés: nincs `UNCERTAIN`** — bizonytalan hozzárendelés most
  nem jelölhető.
- `start_ms`/`end_ms` egész ms (a Python másodpercet ad → átváltás az
  integrációnál). `turn_id` 0-tól indul.
- `similarity`, `asr_confidence` nullázható: a Python jelenleg egyiket sem
  adja ki; a hasonlóság ott beszélőnként (klaszterenként) számolódik, nem
  megszólalásonként.
- `ON DELETE CASCADE` a `recording`-ra (beteg → felvétel → megszólalás).

## Miért JSONB az `allitas.forras` és `eloallito`

Három különböző forrástípust kell hordoznia egy oszlopban: szöveg
char-offset, kép bbox, hang `felvetel_id`+`turn_id`. Normalizált táblával
ez három nullolható oszlopcsoport lenne. Ára: nincs FK-kényszer rajta, a
`turn_id` érvényességét kódban kell ellenőrizni.

## Miért nincs FK a `hozzaferes_naplo.paciens_id`-n

Szándékos. A beteg törlése után is meg kell maradnia az audit-nyomnak,
hogy ki nézte meg korábban — egy `ON DELETE CASCADE` pont a bizonyítékot
törölné.

## `allitas` / `allitas_jovahagyas` viszonya

1:N, nem 1:1 — megőrzi a jóváhagyási előzményt, ha az orvos meggondolja
magát. **Konvenció**: mindig a legfrissebb `idopont` az érvényes állapot;
ezt minden lekérdezésnél következetesen kell alkalmazni.

## `allitas.claim_type` és `bizonytalansag` — CHECK-invariáns

```sql
CONSTRAINT rule_has_no_uncertainty  CHECK (tipus <> 'szabaly' OR bizonytalansag IS NULL);
CONSTRAINT model_needs_uncertainty  CHECK (tipus <> 'modell'  OR bizonytalansag IS NOT NULL);
```

Ugyanez a Java-oldali `Allitas` (majdani) entitáson is legyen kikényszerítve,
hogy egy LLM-kimenet séma szinten se tűnhessen szabály-alapúnak.

## `labor.ertek_num` vs `ertek_szoveg`

`ertek_num NUMERIC` a motoroknak (összehasonlítás referencia-
tartománnyal). Cenzorált (`<0,5`) vagy kvalitatív (`negatív`) eredményt
NEM kerekítünk bele csendben — vagy `ertek_szoveg`-be kerül, vagy az
import elutasítja a sort. FHIR `Quantity` (value+comparator+unit) a
"helyes" hosszú távú megoldás, de most nem építjük meg — dokumentált
tudatos egyszerűsítés.

## `dokumentum.kinyert_szoveg`

Kötelező mező, ha a dokumentumra bármilyen `allitas` char-offsettel
hivatkozik. Enélkül a provenance-visszakeresés ("Forrás megtekintése")
nem tud a pontos szövegrészre ugrani.

## `paciens.taj` formátum

`VARCHAR(9)`, `UNIQUE`, `CHECK (taj ~ '^[0-9]{9}$')`. Sose `INT` — vezető
nulla lehet a TAJ-ban, és van CDV ellenőrzőösszege (azt Java-oldalon
validáljuk, nem SQL CHECK-kel).

**Nullázható** (V2 migráció óta). Az SBO-ban lehet azonosítatlan vagy
külföldi beteg, akinek nincs vagy nem ismert a TAJ-a. A `UNIQUE` ettől még
működik: Postgresben több `NULL` nem ütközik egymással.

## Beteg-azonosítás összetett kulccsal

`findByNameAndBirthDateAndMotherName` — `List`, NEM `Optional`. Két
egyező demográfiai adatú beteg lehetséges, az adatbázis ezt nem zárja ki
(és nem is szabad, mert épp ezért létezik külön a TAJ).
