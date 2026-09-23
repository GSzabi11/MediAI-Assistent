# Hangfelismerő modul — integrációs jegyzet

A teljes fejlesztői átadó dokumentum (hardver, telepítés, ASR/LLM
mérések, nyitott feladatok) itt legyen elhelyezve változtatás nélkül:

**`docs/voice-module-handoff.md`** ← ide másold be a `projekt_atadas.md`
tartalmát a repóból.

Ez a fájl csak azt rögzíti, ami a **backend-integrációt** érinti — a
hangmodul fejlesztési részleteit a fenti dokumentum tartalmazza, azt ne
duplikáld ide.

## A határ: a hangfolyam NEM megy át a Spring backenden

Böngésző → WebSocket → Python ASR-szolgáltatás közvetlenül. A Spring csak
a validált végeredményt kapja (megszólalások + kinyert `allitas`-ok).
A Spring a nyilvántartás rendszere, nem audio-relay.

## Leképezés a fő adatmodellre

| Hangmodul (Python, `validate()`) | Fő rendszer (`allitas`) |
|---|---|
| `forras_turn_ids` | `forras` JSONB: `{felvetel_id, turn_id}` |
| kinyert tünet/mérés | `allitas`, `tipus = 'modell'` |
| KITALÁLT / ÁTVETT → null | az `allitas` nem jön létre |
| ROSSZ FORRÁS / NÉV NEM EGYEZIK | létrejön, `sulyossag = 'warning'` |
| orvosi jóváhagyás | `allitas_jovahagyas` sor |
| `LLM_MODEL`, ASR-modell neve | `eloallito` JSONB — MINDKETTŐ rögzítve,
  nem csak a kinyerő LLM |

## `doctor_id`

A `felhasznalo.id` — a hangprofil a felhasználói fiók adata, nem külön
azonosítási réteg (`hangprofil.felhasznalo_id` FK).

## Szerepkiosztás: háromértékű, nem bináris

`megszolalas.beszelo`: `orvos | beteg | bizonytalan`. Az SBO-eset explicit
tartalmaz hozzátartozót — kétértékű felosztásnál a hozzátartozó kijelentése
kényszerűen a beteghez kerülne, ami anamnézis-hibát okoz.

## Amíg nincs integrálva

Ne feltételezd, hogy a `felvetel`/`megszolalas` tábla vagy bármilyen Java
osztály már létezik ehhez — a `projekt_atadas.md` állapota szerint ez egy
önálló Python prototípus, integráció még nem történt.
