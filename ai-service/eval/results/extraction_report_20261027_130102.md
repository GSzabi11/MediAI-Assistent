# Strukturált kinyerés — 5 eset × 3 futás modellenként

| Modell | Felidézés | Precizitás | Sikertelen futás | Súlyos hiba | Téves tagadás | Többlet (nem hangzott el) | Kitalált részlet (őr törölte) | Névtorzítás | Rossz forrás | Idő (medián) | Első hívás |
|---|---|---|---|---|---|---|---|---|---|---|---|
| medgemma:latest | 114/174 = 65.5% (66–66%) | 111/140 = 79.3% | 0/15 | 9 | 3 | 17 | 12 | 0 | 3/57 | 10.4 s | 17.2 s |
| qwen3.5:9b | 150/174 = 86.2% (86–86%) | 123/123 = 100.0% | 0/15 | 0 | 3 | 0 | 0 | 0 | 0/78 | 27.1 s | 42.5 s |
| medgemma:27b | 165/174 = 94.8% (95–95%) | 123/128 = 96.1% | 0/15 | 0 | 0 | 5 | 1 | 0 | 0/78 | 199.5 s | 303.4 s |

Felidézés: az elvárt elemek aránya (tünet, tagadás, részlet, gyógyszer, allergia, előzmény, üres lista ahol nincs); zárójelben a futásonkénti tartomány. Precizitás: a kinyert tünetek, gyógyszerek, allergiák közül az elvárttal egyezők aránya (az előzmény-többlet nem számít bele). Súlyos hiba: tagadott tünet jelenlévőként, kitalált vagy felcserélt gyógyszer/allergia. Téves tagadás: jelen lévő vagy meg nem válaszolt tünet tagadottként. Rossz forrás: a hivatkozott megszólalásban és az előtte álló kérdésben nincs ott az elem (az ellenőrzött, megtalált elemek közül). Névtorzítás: a gyógyszer / allergia neve egy elvárt névhez hasonló, de eltér (az ékezet nem számít); ilyenkor az elem hiányzónak számít. Kitalált részlet: a modell olyan időpontot / helyet / jelleget írt, amely nem hangzott el, vagy egy másik tünettől vette át; a pipeline őre törölte, mielőtt a pontozás látta volna (a modell hibája, a rendszer kimenetében nincs). Az idő mediánja az első (modellbetöltő) hívás nélkül.

## medgemma:latest

Kategóriánként: jelen lévő tünet 48/54; tagadás 6/27; részlet 18/33; gyógyszer 24/24; allergia 9/12; előzmény 3/18; üres allergialista 3/3; üres gyógyszerlista 3/3

A pipeline saját őrei: kitalált/átvett részlet törölve 12, ROSSZ FORRÁS jelzés 12, névjelzés 6.

- 1. kör, mellkas (17.2 s, 10/15): hiányzik: tagadás: láz; hiányzik: tagadás: hányás; hiányzik: részlet: mellkasi fájdalom terhelésre romlik; hiányzik: részlet: izzadás ideje: reggel; hiányzik: előzmény: dohányzás
- 1. kör, hasi (28.8 s, 8/11): SÚLYOS: tagadott tünet jelenlévőként: székletpanasz (hasmenés/székrekedés); SÚLYOS: kitalált gyógyszer: 'metformin'; SÚLYOS: felcserélt lista: 'ibuprofen' a(z) gyógyszer között; téves tagadás: jelen lévő tünet tagadottként: láz; hiányzik: láz; hiányzik: tagadás: székletpanasz (hasmenés/székrekedés); hiányzik: allergia: ibuprofen; rossz forrás: étvágytalanság: [2]; többlet, nem hangzott el (kézzel nézd át): tünet: menstruáció; többlet, nem hangzott el (kézzel nézd át): tünet: gyógyszer; többlet, nem hangzott el (kézzel nézd át): tünet: allergiás; többlet, nem hangzott el (kézzel nézd át): előzmény: dohányzás; többlet, nem hangzott el (kézzel nézd át): előzmény: alkohol; többlet, elhangzott: tünet: ibuprofen; többlet, elhangzott: tünet: csalánkiütés; többlet, elhangzott: tünet: fogamzásgátló; többlet, elhangzott: allergia: csalánkiütés
- 1. kör, idos (9.8 s, 6/10): hiányzik: tagadás: elesés; hiányzik: részlet: kézgyengeség kezdete: reggel; hiányzik: előzmény: pitvarfibrilláció; hiányzik: előzmény: cukorbetegség
- 1. kör, asztma (8.6 s, 7/13): hiányzik: köhögés; hiányzik: tagadás: köpet; hiányzik: tagadás: láz; hiányzik: tagadás: mellkasi fájdalom; hiányzik: részlet: nehézlégzés kezdete: két órája; hiányzik: előzmény: asztma
- 1. kör, fejfajas (11.5 s, 7/9): hiányzik: részlet: hányás száma: kétszer; hiányzik: előzmény: migrén
- 2. kör, mellkas (11.7 s, 10/15): hiányzik: tagadás: láz; hiányzik: tagadás: hányás; hiányzik: részlet: mellkasi fájdalom terhelésre romlik; hiányzik: részlet: izzadás ideje: reggel; hiányzik: előzmény: dohányzás
- 2. kör, hasi (28.5 s, 8/11): SÚLYOS: tagadott tünet jelenlévőként: székletpanasz (hasmenés/székrekedés); SÚLYOS: kitalált gyógyszer: 'metformin'; SÚLYOS: felcserélt lista: 'ibuprofen' a(z) gyógyszer között; téves tagadás: jelen lévő tünet tagadottként: láz; hiányzik: láz; hiányzik: tagadás: székletpanasz (hasmenés/székrekedés); hiányzik: allergia: ibuprofen; rossz forrás: étvágytalanság: [2]; többlet, nem hangzott el (kézzel nézd át): tünet: menstruáció; többlet, nem hangzott el (kézzel nézd át): tünet: gyógyszer; többlet, nem hangzott el (kézzel nézd át): tünet: allergiás; többlet, nem hangzott el (kézzel nézd át): tünet: allergiás; többlet, nem hangzott el (kézzel nézd át): előzmény: dohányzás; többlet, nem hangzott el (kézzel nézd át): előzmény: alkohol; többlet, elhangzott: tünet: fogamzásgátló; többlet, elhangzott: tünet: ibuprofen; többlet, elhangzott: tünet: csalánkiütés; többlet, elhangzott: allergia: csalánkiütés
- 2. kör, idos (8.3 s, 6/10): hiányzik: tagadás: elesés; hiányzik: részlet: kézgyengeség kezdete: reggel; hiányzik: előzmény: pitvarfibrilláció; hiányzik: előzmény: cukorbetegség
- 2. kör, asztma (8.4 s, 7/13): hiányzik: köhögés; hiányzik: tagadás: köpet; hiányzik: tagadás: láz; hiányzik: tagadás: mellkasi fájdalom; hiányzik: részlet: nehézlégzés kezdete: két órája; hiányzik: előzmény: asztma
- 2. kör, fejfajas (10.3 s, 7/9): hiányzik: részlet: hányás száma: kétszer; hiányzik: előzmény: migrén
- 3. kör, mellkas (14.0 s, 10/15): hiányzik: tagadás: láz; hiányzik: tagadás: hányás; hiányzik: részlet: mellkasi fájdalom terhelésre romlik; hiányzik: részlet: izzadás ideje: reggel; hiányzik: előzmény: dohányzás
- 3. kör, hasi (28.9 s, 8/11): SÚLYOS: tagadott tünet jelenlévőként: székletpanasz (hasmenés/székrekedés); SÚLYOS: kitalált gyógyszer: 'metformin'; SÚLYOS: felcserélt lista: 'ibuprofen' a(z) gyógyszer között; téves tagadás: jelen lévő tünet tagadottként: láz; hiányzik: láz; hiányzik: tagadás: székletpanasz (hasmenés/székrekedés); hiányzik: allergia: ibuprofen; rossz forrás: étvágytalanság: [2]; többlet, nem hangzott el (kézzel nézd át): tünet: menstruáció; többlet, nem hangzott el (kézzel nézd át): tünet: gyógyszer; többlet, nem hangzott el (kézzel nézd át): tünet: allergiás; többlet, nem hangzott el (kézzel nézd át): tünet: allergiás; többlet, nem hangzott el (kézzel nézd át): előzmény: dohányzás; többlet, nem hangzott el (kézzel nézd át): előzmény: alkohol; többlet, elhangzott: tünet: fogamzásgátló; többlet, elhangzott: tünet: ibuprofen; többlet, elhangzott: tünet: csalánkiütés; többlet, elhangzott: allergia: csalánkiütés
- 3. kör, idos (8.2 s, 6/10): hiányzik: tagadás: elesés; hiányzik: részlet: kézgyengeség kezdete: reggel; hiányzik: előzmény: pitvarfibrilláció; hiányzik: előzmény: cukorbetegség
- 3. kör, asztma (7.8 s, 7/13): hiányzik: köhögés; hiányzik: tagadás: köpet; hiányzik: tagadás: láz; hiányzik: tagadás: mellkasi fájdalom; hiányzik: részlet: nehézlégzés kezdete: két órája; hiányzik: előzmény: asztma
- 3. kör, fejfajas (10.6 s, 7/9): hiányzik: részlet: hányás száma: kétszer; hiányzik: előzmény: migrén

## qwen3.5:9b

Kategóriánként: jelen lévő tünet 54/54; tagadás 24/27; részlet 15/33; gyógyszer 24/24; allergia 12/12; előzmény 15/18; üres allergialista 3/3; üres gyógyszerlista 3/3

A pipeline saját őrei: kitalált/átvett részlet törölve 0, ROSSZ FORRÁS jelzés 3, névjelzés 0.

- 1. kör, mellkas (42.5 s, 11/15): téves tagadás: bizonytalan válasz tagadottként: hányinger (nem válaszolt rá); hiányzik: részlet: mellkasi fájdalom kezdete: tegnap este; hiányzik: részlet: mellkasi fájdalom terhelésre romlik; hiányzik: részlet: izzadás ideje: reggel; hiányzik: előzmény: dohányzás
- 1. kör, hasi (29.8 s, 9/11): hiányzik: részlet: hasi fájdalom kezdete: tegnap délután; hiányzik: részlet: hányás ideje: ma reggel
- 1. kör, idos (18.3 s, 8/10): hiányzik: tagadás: elesés; hiányzik: részlet: kézgyengeség kezdete: reggel
- 1. kör, asztma (36.4 s, 13/13): hibátlan
- 1. kör, fejfajas (25.9 s, 9/9): hibátlan
- 2. kör, mellkas (43.9 s, 11/15): téves tagadás: bizonytalan válasz tagadottként: hányinger (nem válaszolt rá); hiányzik: részlet: mellkasi fájdalom kezdete: tegnap este; hiányzik: részlet: mellkasi fájdalom terhelésre romlik; hiányzik: részlet: izzadás ideje: reggel; hiányzik: előzmény: dohányzás
- 2. kör, hasi (29.8 s, 9/11): hiányzik: részlet: hasi fájdalom kezdete: tegnap délután; hiányzik: részlet: hányás ideje: ma reggel
- 2. kör, idos (17.1 s, 8/10): hiányzik: tagadás: elesés; hiányzik: részlet: kézgyengeség kezdete: reggel
- 2. kör, asztma (27.8 s, 13/13): hibátlan
- 2. kör, fejfajas (21.9 s, 9/9): hibátlan
- 3. kör, mellkas (29.1 s, 11/15): téves tagadás: bizonytalan válasz tagadottként: hányinger (nem válaszolt rá); hiányzik: részlet: mellkasi fájdalom kezdete: tegnap este; hiányzik: részlet: mellkasi fájdalom terhelésre romlik; hiányzik: részlet: izzadás ideje: reggel; hiányzik: előzmény: dohányzás
- 3. kör, hasi (26.4 s, 9/11): hiányzik: részlet: hasi fájdalom kezdete: tegnap délután; hiányzik: részlet: hányás ideje: ma reggel
- 3. kör, idos (15.7 s, 8/10): hiányzik: tagadás: elesés; hiányzik: részlet: kézgyengeség kezdete: reggel
- 3. kör, asztma (28.2 s, 13/13): hibátlan
- 3. kör, fejfajas (21.9 s, 9/9): hibátlan

## medgemma:27b

Kategóriánként: jelen lévő tünet 54/54; tagadás 24/27; részlet 30/33; gyógyszer 24/24; allergia 12/12; előzmény 15/18; üres allergialista 3/3; üres gyógyszerlista 3/3

A pipeline saját őrei: kitalált/átvett részlet törölve 1, ROSSZ FORRÁS jelzés 0, névjelzés 3.

- 1. kör, mellkas (303.4 s, 13/15): hiányzik: tagadás: hányás; hiányzik: előzmény: dohányzás; többlet, nem hangzott el (kézzel nézd át): tünet: fájdalom súlyosbodása
- 1. kör, hasi (278.2 s, 11/11): hibátlan
- 1. kör, idos (197.8 s, 10/10): hibátlan
- 1. kör, asztma (271.6 s, 13/13): hibátlan
- 1. kör, fejfajas (159.1 s, 8/9): hiányzik: részlet: hányás száma: kétszer
- 2. kör, mellkas (272.3 s, 13/15): hiányzik: tagadás: hányás; hiányzik: előzmény: dohányzás; többlet, nem hangzott el (kézzel nézd át): tünet: fájdalom; többlet, nem hangzott el (kézzel nézd át): tünet: fájdalom
- 2. kör, hasi (234.9 s, 11/11): hibátlan
- 2. kör, idos (159.1 s, 10/10): hibátlan
- 2. kör, asztma (194.8 s, 13/13): hibátlan
- 2. kör, fejfajas (157.0 s, 8/9): hiányzik: részlet: hányás száma: kétszer
- 3. kör, mellkas (251.5 s, 13/15): hiányzik: tagadás: hányás; hiányzik: előzmény: dohányzás; többlet, nem hangzott el (kézzel nézd át): tünet: fájdalom; többlet, nem hangzott el (kézzel nézd át): tünet: fájdalom
- 3. kör, hasi (226.6 s, 11/11): hibátlan
- 3. kör, idos (148.3 s, 10/10): hibátlan
- 3. kör, asztma (201.2 s, 13/13): hibátlan
- 3. kör, fejfajas (150.7 s, 8/9): hiányzik: részlet: hányás száma: kétszer