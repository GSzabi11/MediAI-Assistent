# Adatmodell-döntések

A teljes ER-diagram a repóban: `docs/er-diagram.png` (töltsd be ide a
legutóbbi drawio-exportot). Ez a fájl a döntések *miértjét* rögzíti, amit
egy diagram nem mutat.

## Táblalista (célállapot — nem mind létezik még migrációban)

`felhasznalo`, `hangprofil`, `paciens`, `ellatas`, `hozzajarulas`,
`dokumentum`, `labor`, `felvetel`, `megszolalas`, `allitas`,
`allitas_jovahagyas`, `hozzaferes_naplo`, később: `gyogyszer`, `allergia`,
`doc_chunk`, `iranyelv_chunk`.

Jelenleg migrálva: `pacient`, `app_user` (= a diagram `felhasznalo`-ja).

## `app_user` (felhasznalo)

Angol elnevezés, a tábla neve `app_user`, mert a `user` Postgresben
foglalt szó. Jelszó sosem tárolódik nyersen: `password_hash` (BCrypt, a
`spring-security-crypto` modulból — a teljes Spring Security még nincs
bekötve). A szerepkör `DOCTOR | ADMIN`, CHECK-kel kikényszerítve.

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
