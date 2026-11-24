# Beszélő-hozzárendelés mérése — útmutató a felvételhez és a kiértékeléshez

Fájlok: `der_forgatokonyv.md` (a felolvasandó párbeszéd, D1), `der.py` (előkészítés + pontozás),
eredmények: `eredmenyek/beszelo_rec<id>_<idő>.json`, a DER-hez `eredmenyek/der/` és `eredmenyek/der_rec<id>_<idő>.json`.

## Fő módszer: beszélő-hozzárendelési pontosság a szövegből (annotálás nélkül) — Szabi döntése, 2026-11-21

A forgatókönyvből ismert, ki mit mond. A rendszer végső, beszélőnként bontott átiratát (a megszólalások szavai a
hozzájuk rendelt szereppel) szóillesztéssel (Levenshtein, ugyanaz a normalizálás, mint a WER-mérésnél: kisbetű,
írásjel nélkül, a szám szavakkal, írásmód-függetlenül) a forgatókönyv szavaihoz igazítjuk, és minden illesztett szónál
megnézzük, hogy a rendszer ugyanahhoz a szereplőhöz (O = `DOCTOR`, B = `PATIENT`) rendelte-e, mint a forgatókönyv.

```powershell
python tools\meresek_2026-11\der.py szoveg --recording <id> --verbose
```

(A rendszer-Pythonnal fut, mint a `wer.py`; kell a `num2words` és a futó Postgres-konténer.)

Kimenet, az AI-átnézés előtt (`asr_speaker`) és után (`speaker`):
- **szó szintű pontosság** — összesen, orvos / beteg szerint, és külön a 7 átfedő betét szavaira;
- **forgatókönyv-sor szintű** — a 75 sorból hánynak a szavai kerültek többségükben a jó szereplőhöz;
- **rendszer-megszólalás szintű** — és hány „vegyes” megszólalás van (két szereplő szavai egyben: a diarizáció nem
  vágta szét a gyors váltást).

**A dolgozatban ez „beszélő-hozzárendelési pontosság”, nem DER**: nem időalapú (nem mér beszéd-időtartamot, hiányt,
téves riasztást), csak a felismert szavak szerepét. Korlátai:
- csak az illesztett szavakon számol — a Whisper által kihagyott szónak nincs szerepe, a többlet szónak nincs párja
  (ezek számát és a WER-t a kimenet külön megadja);
- egy rosszul felismert, de helyettesítésként illesztett szó szerepe is számít (ez kívánatos: a szó hallatszott, csak
  rosszul írta le);
- az átfedő betéteknél a szórend nem egyértelmű (a Whisper egymás után írja le az egyszerre mondottakat), ezért azokat
  külön soron mutatja, a fő számot átfedés nélkül is;
- ugyanazok a korlátok, mint lent: felolvasott beszéd, ismert beszélőszám, az orvos hangprofilja ugyanarról az emberről,
  kis minta.

Lépések: 1. előkészület és 2. felvétel (lent), aztán rögtön a fenti parancs. A 3–5. lépés (Audacity-annotálás + DER)
**opcionális** — csak ha időalapú, irodalommal összevethető DER is kell.

## DER (opcionális, kézi annotációval) — mit mérünk

| Mérőszám | Mit jelent | Miért kell |
|---|---|---|
| **DER** (diarization error rate) | (hiány + téves riasztás + beszélő-összetévesztés) / referencia-beszédidő, a beszélőcímkék optimális párosításával | szabványos, irodalommal összevethető |
| **Szerepkiosztási hiba** | ugyanez, de rögzített párosítással: O = `DOCTOR`, B = `PATIENT` (pyannote `IdentificationErrorRate`) | a klinikailag érdekes szám: hány másodpercnyi beszéd került rossz szerephez (pl. a beteg mondata orvosiként) |
| **Megszólalás-pontosság** | a rendszer megszólalásai közül hánynak jó a szerepe (a vele időben legtöbbet átfedő referencia-beszélőhöz mérve) | ez látszik a felületen és ez megy az összefoglalóba / csoportokba |

Mindegyik két változatban: **az AI-átnézés előtt** (`utterance.asr_speaker`: diarizáció + hanglenyomat) és **után**
(`utterance.speaker`) — így az átnézés beszélő-javításának hatása is látszik. A `--nyers` kapcsolóval a pyannote-pipeline
saját kimenetének DER-je is (ugyanaz a helyi modell, `num_speakers=2`), a szóhoz rendelés nélkül.

Collar: ±0,25 s (a NIST md-eval szokása; pyannote-ban `collar=0.5`, mert ott a teljes szélesség) és 0. Átfedés:
beszámítva és kizárva.

## 1. Előkészület (egyszer, ≈ 10 perc)

1. Indítsd a rendszert (`start-all`), ellenőrizd, hogy a hangszolgáltatás fut (`http://127.0.0.1:8002/health`).
2. **A Profil oldalon a te hangprofilod legyen aktív és `READY`.** Az orvos szerepét annak kell olvasnia, akinek a
   profilja aktív — a rendszer a hanglenyomathoz méri, ki az orvos. Ha a másik ember olvasná az orvost, a szerepek
   felcserélődnének (ez nem diarizációs hiba, hanem rossz mérés).
3. Hozz létre egy új, kitalált beteget (pl. „DER Teszt”, TAJ nélkül vagy kitalált TAJ-jal), és nyiss neki egy ellátást
   (**Ambuláns** típus). Jegyezd fel a beteg id-jét.
4. A felolvasó társadnak add oda a `der_forgatokonyv.md`-t (nyomtatva vagy külön képernyőn). Egyszer olvassátok át
   hangosan felvétel nélkül, főleg a `‹…›` átfedéses helyeket.

## 2. Felvétel (≈ 2 × 5 perc)

- Csendes szoba, egy mikrofon (a laptopé vagy egy asztali), **a két beszélő nagyjából egyforma távolságban**
  (50–80 cm), egymással szemben. Ne fogjátok a laptopot, ne gépeljetek közben.
- A felvételt az ambuláns ellátáson belül indítsd. Technikai megjegyzés: a felület csak az echo-ellátásnál vált diktáló
  módra; ambuláns ellátásnál a felvétel a kétbeszélős (`sbo` nevű) feldolgozással megy, ami a diarizációtól fix 2
  beszélőt kér — a tárolt `recording.mode` ezért `SBO` lesz, ez így helyes.
- Felvétel indítása után 2 mp csend, aztán O01-től B75-ig végig, megállás nélkül. Ha valaki eltéveszt egy szót,
  javítsa ki és menjetek tovább (a referencia a ténylegesen elhangzottat követi, a forgatókönyv csak ellenőrzés).
  Ha egy egész mondat kimarad, az sem baj — a pontozó szól róla, és az annotáció úgyis a valóságot rögzíti.
- Az átfedéseknél (`‹B: Dehogynem!›`) a fő beszélő **ne álljon meg**, a másik vágjon bele. A rövid válaszokat
  (`Igen.`, `Nem.`, `Hatvannégy.`) gyorsan, szünet nélkül.
- **Két felvétel** (D1a, D1b) ugyanazzal a beállítással. Ha van időtök, egy harmadik más elrendezéssel (pl. a beteg
  2 m-re a mikrofontól) — ez külön sorként mehet a táblázatba.
- Várd meg, amíg a felvétel `DONE` lesz (átirat + átnézés kész). Az összefoglaló / csoportok nem kellenek a méréshez.

Felvételek id-je:

```powershell
ai-service\voice\ai_env\Scripts\python tools\meresek_2026-11\der.py lista --patient <beteg-id>
```

## 3. Előkészítés az annotáláshoz (felvételenként 1 parancs)

```powershell
ai-service\voice\ai_env\Scripts\python tools\meresek_2026-11\der.py elokeszit --recording <id>
```

Az `eredmenyek/der/` mappába kerül:
- `rec<id>.wav` — a megőrzött hangfelvétel 16 kHz mono wav-ként (Audacity a WebM-et FFmpeg nélkül nem nyitja meg);
- `rec<id>_vazlat.txt` — a beszédszakaszok **Silero VAD** szerint, `?` címkével. Ez a diarizációtól független váz,
  csak a munkát gyorsítja (nem kell minden szakaszt kézzel kijelölni);
- `rec<id>_rendszer.txt` — a rendszer megszólalásai címkesávként (`D #12` = orvos, 12. megszólalás; `*` = az AI-átnézés
  módosította a beszélőt). **Ezt csak az annotálás befejezése UTÁN nyisd meg**, különben a referencia akaratlanul a
  rendszer határaihoz igazodik.

## 4. Kézi annotálás Audacityben (felvételenként ≈ 40–60 perc)

1. Fájl → Importálás → Hang: `rec<id>.wav`; Fájl → Importálás → Címkék: `rec<id>_vazlat.txt`.
2. Hallgasd végig szakaszonként (kijelölés + szóköz), a forgatókönyvvel a kezedben. Minden `?` címkét írj át:
   **`O`** (orvos) vagy **`B`** (beteg).
3. Szabályok (ugyanazok minden felvételnél — a dolgozatba is ezeket írd):
   - A határ a hallható beszéd eleje / vége (a belégzés nem beszéd). A címke szélét húzással igazítsd; nagyítsd fel,
     a hullámforma alapján ~0,1 s pontosság elég (a collar ±0,25 s).
   - Ha egy VAD-szakaszban két beszélő van (gyors váltásnál gyakori), vágd ketté: jelöld ki a második beszélő részét,
     Ctrl+B → új címke, és igazítsd a régit.
   - Ugyanannak a beszélőnek a 0,5 s-nál rövidebb szünete egy címkén belül maradhat; hosszabb szünetnél két címke.
   - **Átfedés**: a fő beszélő címkéje megy tovább, a belevágó beszélő kap egy saját címkét az átfedő időre (a két
     címke időben egymásba lóg — az Audacity ezt engedi). A forgatókönyv 7 ilyen helyet tartalmaz (`‹…›`), de ha
     máshol is belevágtatok egymás szavába, azt is jelöld.
   - A rövid visszajelzés (`Aha.`, `Igen, igen.`) is beszéd, kap címkét. Köhögés, nevetés, papírzörgés nem — a VAD
     által ilyenre tett címkét **töröld** (minden, ami nem `O` / `B`, „egyéb beszélőnek” számít).
4. Fájl → Exportálás → Címkék exportálása → `eredmenyek/der/rec<id>_ref.txt`.

## 5. Pontozás

```powershell
ai-service\voice\ai_env\Scripts\python tools\meresek_2026-11\der.py pontoz --recording <id> --ref tools\meresek_2026-11\eredmenyek\der\rec<id>_ref.txt --nyers --verbose
```

Először ellenőrzi a referenciát a forgatókönyvvel (a fő megszólalások O/B sorrendje, 75 váltás). 100% alatti egyezésnél
kiírja, hol tér el — ilyenkor nézd meg, hogy a felolvasásban maradt-e ki mondat, vagy az annotálásban van hiba.
Utána a táblázatok (markdown, a dolgozatba másolható), `--verbose`-zal a rossz szerepű megszólalások listája
(idő, hasonlóság, szöveg — a szöveg a kitalált forgatókönyvé). A nyers JSON az `eredmenyek/`-be kerül.
A `--nyers` a pyannote-ot újra lefuttatja a GPU-n (≈ fél perc), közben ne fusson más GPU-feladat.

Ezután nyisd meg Audacityben a `rec<id>_rendszer.txt`-t is a referencia alá: a hibák helye így szemmel is látszik
(jó ábra a dolgozatba is: egy 30 mp-es részlet a két címkesávval).

## Módszertan — mit lehet ebből állítani és mit nem

**Miért kézi annotáció, és miért nem a forgatókönyv sorrendje a referencia?** A forgatókönyvből csak a beszélők
*sorrendje* ismert, az időpontok nem. Ha a sorrendet a rendszer saját megszólalás-időpontjaihoz igazítanánk, a
referencia a mért rendszer határait és hibáit örökölné (körkörös mérés: egy kettévágott vagy összevont megszólalás
hibája eltűnne). Ezért a referencia időpontjai a hallás + hullámforma alapján, kézzel készülnek, és a forgatókönyv csak
ellenőrzésre szolgál. A Silero VAD-váz a pyannote-tól független modell, de a határokat így is „sugallja”: ezt az
enyhíti, hogy minden határt meghallgatva igazítunk, és a rendszer kimenetét csak az annotálás után nézzük meg.

**Korlátok (a dolgozatba is):**
- **Egy annotátor** (a szerző), nincs annotátorok közötti egyezés. A határok bizonytalansága ~0,1–0,2 s — ezért a
  collar ±0,25 s; a collar nélküli szám a felső becslés.
- **Felolvasott, nem spontán beszéd**: a valódi rendelői beszélgetésnél kevesebb a belevágás, a félbehagyott mondat és a
  zaj; a két felolvasó nem valódi beteg és orvos. Az eredmény a kedvező esetet méri.
- **Kedvező beállítás**: ismert beszélőszám (a kétbeszélős feldolgozás fix 2 beszélőt kér), az orvos hangprofilja ugyanarról az
  emberről, ugyanazzal a mikrofonnal. Hozzátartozó (3. beszélő) nincs a mérésben.
- **A rendszer kimenete nem tiszta diarizáció**: a megszólalás a Whisper szó-időbélyegeiből áll össze (a szó a középpontja
  szerinti beszélőhöz kerül), így (a) egy megszólalás az első szó elejétől az utolsó végéig tart, a belső szünet is
  „beszéd” → téves riasztás; (b) a Whisper szóhatárai pontatlanabbak a pyannote-énál. Ezért külön a nyers pyannote-DER
  (a modell teljesítménye) és a rendszer DER-je (amit az orvos lát).
- **Átfedés**: a rendszer a kizárólagos (`exclusive`) diarizációt használja, egy időpillanatban egy beszélőt ismer — az
  átfedő beszéd második beszélője mindig „hiány”. Ez a felolvasásban kevés (7 rövid betét, a beszédidő ~1–3%-a), a táblázat
  az átfedés kizárásával is mutatja.
- **Kis minta**: egy forgatókönyv, két felvétel (≈ 10 perc); megbízhatósági intervallum nem számolható értelmesen, az
  eredmény leíró jellegű. Más hangok, mikrofon, terem más számot adhat.

## A dolgozatba (8.4.8) — váz (szöveg-alapú, fő módszer)

> A beszélő-hozzárendelést egy kitalált, kétszereplős ambuláns (kardiológiai kontroll) párbeszéden mértük (75
> megszólalás, ≈ 540 szó, 7 szándékos átfedés, ≈ 5 perc), amelyet két személy olvasott fel az alkalmazás felvételével,
> ambuláns ellátáson belül, [N] alkalommal; az orvost a hangprofil tulajdonosa olvasta. A rendszer beszélőnként bontott
> végső átiratát a WER-méréssel azonos normalizálás után szóillesztéssel a forgatókönyvhöz igazítottuk, és szavanként
> összevetettük a hozzárendelt szerepet a forgatókönyv szerintivel. Az illesztett szavak [x]%-a került a jó
> szereplőhöz az AI-átnézés előtt, [y]%-a utána (orvos [o]%, beteg [b]%); a 75 forgatókönyv-sorból [s] került
> többségében a jó szereplőhöz, és [v] rendszer-megszólalás tartalmazott két szereplő szavait. Ez nem időalapú DER: a
> kihagyott és a többlet szavakat nem méri (ezeket a WER mutatja, [w]%). [Korlátok: felolvasott beszéd, ismert
> beszélőszám, kis minta.]

## A dolgozatba — váz (ha a kézi annotációs DER is elkészül)

> A beszélő-szétválasztást egy kitalált, kétszereplős ambuláns (kardiológiai kontroll) párbeszéden (75 megszólalás, 7 szándékos átfedés,
> ≈ 5 perc) mértük, amelyet két személy olvasott fel az alkalmazás felvételével, ambuláns ellátáson belül, [N] alkalommal. A referenciát
> kézzel annotáltuk (Audacity, beszélőnként címkézett szakaszok, átfedés külön jelölve), a forgatókönyv a sorrend
> ellenőrzésére szolgált. A hibát a pyannote.metrics könyvtárral számoltuk ±0,25 s-os collarral: a szabványos DER
> [x]%, a klinikailag lényeges szerepkiosztási hiba (rögzített orvos/beteg párosítással) az AI-átnézés előtt [y]%,
> utána [z]%; a megszólalások [p]%-ának volt helyes a szerepe. A nyers pyannote-kimenet DER-je [r]%. [Korlátok: egy
> annotátor, felolvasott beszéd, ismert beszélőszám, kis minta.]
