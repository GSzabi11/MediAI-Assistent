# Válaszok a bírálat 6 kérdésére — vázlat az előadáshoz

Forrás a számokhoz: a dolgozat (MediAI_IDK_2026_vegleges_v2_szerkeszt.docx: pénzügyi terv, D2. táblázat) és a
2026-11-15-i mérések (`jelentes.md`). A **[ellenőrizendő]** jelű számok becslések (hardverár, piaci adat), ezeket az
előadás előtt egy árajánlattal / forrással érdemes megerősíteni.

Javasolt elhelyezés: egy „Bírálói kérdések” blokk 2–3 diával a végén (Q&A előtt), vagy a kérdéseket a meglévő diák
mellé szétosztva (üzleti modell → 1–3., technikai → 4–5., terjeszkedés → 6.). Kérdésenként egy 30–45 mp-es szóbeli
válasz és egy diára kerülő egysoros számadat.

---

## 1. A hardver ára a licencdíjhoz képest (4–8 évnyi licencdíj?)

**Rövid válasz:** az arány az intézmény méretén múlik, és a hardver nem orvosonként skálázódik. Egy 100 orvosos
intézmény évi licencdíja 24 M Ft (240 000 Ft × 100). Egy 48 GB-os GPU-s szerver kb. 8–12 M Ft **[ellenőrizendő]**,
ez kb. 0,4–0,5 évnyi licencdíj. A bíráló által jelzett 4–8 év a kis, 5–10 orvosos felhasználásra igaz. Ott ezért nem
saját szerver, hanem megosztott (fenntartói / regionális) szerver vagy lízing a reális út (ld. 6. kérdés).

**Számok:**
- A D2. táblázat szerint egy 48 GB-os GPU-n ≈ 3,8 perc GPU-idő/beteg, ≈ 230 beteg/nap. Ez kb. 23 orvosi műszak
  GPU-nként naponta (10 beteg/műszak).
- A GPU-idő méretezése: a dolgozat és a 9. dia szerint 100 orvoshoz kb. 2 GPU kell (GPU-nként ≈ 23 orvosi műszak/nap;
  egy orvos nem dolgozik minden nap teljes műszakot). Ha mind a 100 orvos minden nap 10 beteget lát (1000 beteg/nap),
  az 24 órás, 60%-os kihasználtsággal 4–5 GPU lenne. **A hardverigényt a GPU-idő szabja meg**, nem a szerver ára;
  ha a bíráló rákérdez, ezt a két számot érdemes megmondani. (A diára a 9. diával egyező „kb. 2 GPU” került.)
- Mérséklési lehetőségek, a mai mérések alapján:
  - Nem minden feladathoz kell 27B: a laborkinyerésben a 4B 92/92, mindhárom seeddel.
  - A hangfeldolgozás a csúcsidőn kívülre ütemezhető (a sor nem veszít feladatot: D2/D3 mérés).
  - A hang 32 kbit/s-mal (2026-11-15 óta így rögzítünk) ≈ 0,38 TB/év 100 orvosra (E mérés).

**Diára:** „100 orvos: licenc 24 M Ft/év vs. szerver ≈ 0,4–0,5 évnyi licenc; a kis osztályoknak megosztott szerver / lízing.”

---

## 2. Felhő vs. on-premise — csak akkor működik az üzleti terv, ha a felhő ki van zárva?

**Rövid válasz:** egyetértek: képesség/költség arányban a felhő ma jobb. Ezért az architektúra **nincs a helyi
futtatáshoz kötve**. A modellréteg feladatonként cserélhető (Ollama / OpenAI-kompatibilis hívás), a szolgáltatások
konténerben futnak. Így ugyanaz a termék futhat a kórház szerverén, a fenntartó adatközpontjában, vagy EU-s szuverén
felhőben is. Az üzleti terv nem azon áll, hogy a felhő tilos, hanem azon, hogy **a betegkontextus ne kerüljön
harmadik országbeli, általános célú felhős modellhez**. Ezt ma a kórházak adatvédelmi gyakorlata és az
EESZT-adatok kezelése miatt valóban nehéz megengedni.

**Érvek:**
- A termék értéke nem a modell. Az ér a legtöbbet, ami a modell körül van: a forráskövetés (állítástípusok, szó
  szerinti idézet szerveroldali ellenőrzése — B mérés: 30/30 elutasítva), a determinisztikus szabályok, a visszavonás
  (10/10, 10/10). Ez felhős modell mellett is ugyanígy működik.
- Ha a felhő megengedetté válik, az üzleti modell **előfizetésre** (orvos/hó) vált, és a hardverköltség eltűnik. Ez a
  kockázat tehát kezelhető, nem végzetes.
- A szigorú változathoz (csak helyben) a termékben ma is ott a kimenő kapu: hálózatra csak a szakirodalmi
  keresőkifejezés megy, betegazonosító nélkül.

**Diára:** „Telepítés-független: kórházi szerver → fenntartói adatközpont → EU-s szuverén felhő; a forráskövetés és a
szabálymotor mindegyikben ugyanaz.”

---

## 3. Bináris visszafejtés — lemásolhatja-e egy rivális?

**Rövid válasz:** technikailag a Java- és Python-kód visszafejthető, ezért nem a kód titkosságára építek. Reálisan
mégis alacsony a veszély, három okból:
1. **Jogi:** a szoftver szerzői jogi védelme (EU 2009/24/EK irányelv, Szjt.) a visszafejtést csak az
   együttműködő-képesség érdekében engedi (6. cikk). Az így kapott kód versenytermékbe nem építhető. A licencszerződés
   kizárja az egyéb visszafejtést és a továbbadást. Az üzleti titok törvény (2018. évi LIV.) csak a jogszerűen
   megszerzett termék visszafejtését engedi, és ezt szerződés korlátozhatja.
2. **Szabályozási — ez a legerősebb gát:** orvostechnikai eszközként (MDR, IIa) a másolat **saját CE-jelölés nélkül
   nem hozható forgalomba az EU-ban**. Ehhez saját minőségirányítás, klinikai értékelés és kijelölt szervezeti audit
   kell, ami a dolgozat szerint ≈ 110 000 EUR és 1–2 év. A kód lemásolása ezt nem pótolja.
3. **Technikai:**
   - A kód a kórház szerverén fut, nem az orvosok gépén. A böngésző csak a felületet kapja, a szerverhez csak a kórház
     informatikusa fér hozzá, szerződés alapján.
   - Csak lefordított, obfuszkált csomagot telepítünk (Java: ProGuard; Python: Nuitka / Cython), aláírt frissítéssel.
   - A modellsúlyok nyíltak (MedGemma), a valódi érték nem a kódban van. Ami nem másolható: a kalibrált beállítások,
     a mérési korpusz és a validációs eredmények, a referenciák és az integrációk.

**Diára:** „A másolat ellen nem a kód titka véd: MDR-tanúsítás (1–2 év, ≈ 110 k EUR) + szerződés + csak szerveren
futó, obfuszkált build.” (Ez összeköthető az iparjogvédelmi táblázattal: szabadalom vs. üzleti titok vs. know-how.)

---

## 4. A 27B pontosabb, de 10–20× lassabb — finomhangolna-e egy kisebb modellt az audiós feladatokra?

**Rövid válasz:** igen, ez a következő lépés, és a mai mérés ehhez megadta az alapot.
- **Hol van értelme:**
  - Ma a 4B a laborkinyerésben hibátlan (92/92), a hangátnézésben viszont gyenge: 0/5 javított szóhiba, 5 rossz
    javítás és 4 elrontott helyes szó, szemben a 27B 3/5-ével és 0 hibájával. Ez 3 seed × 3 futásban bitre azonosan
    jött ki, tehát a modell képessége, nem véletlen.
  - Az audiós feladatok szűkek és kötött formátumúak (célzott kérdés → JSON, hat csoport kötött sémával). Ilyen
    feladatra egy kis modell LoRA-finomhangolása jól működik.
- **Hogyan, betegadat nélkül:** desztilláció. A 27B (vagy egy erősebb modell) címkéz szintetikus és felolvasott
  párbeszédeket, és a 4B LoRA-adaptere ezen tanul. Az eredményt ugyanazzal a mérőkörnyezettel hasonlítom össze
  (review_eval, findings_eval). Mivel a futások determinisztikusak, a különbség valódi különbség.
- **Fenntartások:**
  - Tanúsított terméknél a modellcsere lényeges változtatás lehet, ezért a finomhangolt modell csak validált kiadásban
    kerülhet be.
  - Az AI Act szerint a finomhangolással a modell „módosítójává” válunk, ez dokumentálási kötelezettség.
  - A 10–20× részben a 8 GB-os GPU következménye: ott a 27B részben a rendszermemóriából fut. 48 GB-on a
    betegenkénti GPU-idő ≈ 16 percről ≈ 3,8 percre csökken (D2. táblázat).
- **Alternatíva:** a hangnál a beszédfelismerő (Whisper) magyar orvosi szókincsre hangolása nagyobb nyereség lehet. Ezt
  a WER-mérés (F) méri ki.

**Diára:** „4B vs 27B hangátnézés: 0/5 vs 3/5 javítás, 100%-os ismételhetőség → finomhangolás (LoRA, desztilláció
szintetikus adaton) a 4B-re, ugyanazzal a mérőkörnyezettel mérve.”

---

## 5. Teljes felvétel vs. streaming beszédfelismerés

**Rövid válasz:** a streaming a **várakozást** csökkenti, a **GPU-időt** nem. A hangfeldolgozás GPU-idejének
nagyobbik része ráadásul nem a beszédfelismerés, hanem az utána futó nyelvi modell. A D2. táblázat szerint az 5 perces
felvétel ≈ 550 s-ából az átnézés ≈ 150 s, a hat csoport ≈ 140 s. Ezeket a streaming nem gyorsítja.

**Kompromisszumok:**

| | Teljes felvétel (ma) | Streaming |
|---|---|---|
| Pontosság | jobb: a diarizáció és a szerepkiosztás a teljes hangot látja, a Whisper kontextussal dolgozik | gyengébb diarizáció (online), magyarul kevesebb jó streaming-modell |
| GPU-terhelés | sorba állítható, csúcson kívülre tolható | minden futó felvétel folyamatosan GPU-t foglal → a kapacitást az **egyidejű** felvételek száma szabja meg |
| Hibatűrés | a hang megmarad, kiesés után magától újraindul (D2 mérés: 370 s) | megszakadt adatfolyamnál a pótlás bonyolult |
| Várakozás | az átirat a felvétel után 2–6 perccel kész | az átirat a felvétel végén kész |
| Adatvédelem | a hang a Springen át, a naplózott úton megy | élő kapcsolat kell a böngésző és a GPU-szolgáltatás között |

**Javasolt középút:** darabolt, közel valós idejű átírás. A felvétel alatt 20–30 mp-es darabokban fut az ASR, így a
felvétel végére kész a nyers átirat. A diarizáció, az átnézés és a csoportok a végén, a teljes hangon futnak. Ez a
várakozást a nyelvi modell lépéseire szűkíti, a pontosság marad.

**Diára:** „Streaming = kisebb várakozás, nem kevesebb GPU-idő; a hang GPU-idejének nagyobbik része a nyelvi modell
(≈ 290 / 550 s) → középút: darabolt ASR felvétel közben, diarizáció + átnézés a végén.”

---

## 6. Elvárható-e, hogy a kórház GPU-szervert vegyen? Hardverrel együtt kellene árulni? Miért V4 + Románia, és nem DACH?

### 6a. Saját GPU-szerver vagy csomag

**Rövid válasz:** egy magyar állami kórháztól az egyedi GPU-szerver-beszerzés lassú és nem reális elvárás. Ezért
három utat kínálnék, a kórház választhat:
1. **Hardverrel együtt, „appliance”-ként, lízingben / bérletben.** Előre konfigurált, validált szerver, havidíjban a
   licenc mellett. Előnye:
   - Az MDR szerint is a validált szoftver–hardver kombinációt szállítjuk.
   - Egy beszerzési tétel.
   - A hardver beruházás helyett működési költség lesz.

   Hátránya: nálunk jelenik meg a tőkeigény és a garanciális logisztika. Ez a pénzügyi tervben külön sort kap, és
   lízingpartnerrel kezelhető.
2. **A kórház saját szervere**, referencia-specifikáció alapján, ahol van informatikai kapacitás és keret.
3. **Megosztott szerver a fenntartó / régió adatközpontjában** a kis intézményeknek. Ez sem nyilvános felhő, a hálózat
   a fenntartóé.

A dolgozat mostani terve (a hardvert az intézmény szerzi be) helyett tehát a fő ajánlat a csomag + lízing lenne. Ez
a bírálat jogos pontja.

### 6b. V4 + Románia vagy DACH

**Rövid válasz:** a DACH fizetőképesebb, és ott egy nyelv elég, ezt elismerem. Ezért nem kizárom, hanem későbbre
teszem. A V4 + Románia az első lépés, mert:
- **Hasonló probléma, hasonló infrastruktúra:** mindenhol nemzeti e-egészségügyi rendszer van szétszórt
  dokumentumokkal (az EESZT-hez hasonló cseh, lengyel, szlovák, román rendszerek **[ellenőrizendő a pontos név]**). A
  „szétszórt betegadat összegzése” ugyanaz a fájdalompont.
- **Az árszint illeszkedik:** a 240 000 Ft/orvos/év (≈ 600 EUR) licenc ott versenyképes. A dolgozatban ez a SAM ≈
  38 Mrd Ft/év.
- **Kisebb a verseny:** németül már több érett AI-dokumentációs és -írnok termék van, erős helyi tőkével és
  KIS-integrációkkal **[ellenőrizendő konkrét nevekkel]**. Ezzel egy kis csapat CE-jelölés előtt nem tud versenyezni.
- **Referencia és költségbázis:** a régiós pilotok olcsóbbak, és referenciát adnak a DACH-belépéshez.

**Őszintén:** a V4 négy külön nyelv (cseh, szlovák, lengyel, román), ezért nyelvi oldalon a DACH egyszerűbb, mert egy
német lokalizáció ~100 millió embert fed le. A kompromisszum: a sorrend V4/Románia → DACH a CE-jelölés és 2–3 hazai
referencia után. Ha egy DACH-partner (pl. egy KIS-szállító) korábban jelentkezik, a sorrend felcserélhető.

**Diára:** „Csomag + lízing a fő ajánlat · V4+RO elsőként: hasonló e-egészségügyi probléma, illeszkedő árszint, kisebb
verseny · DACH a CE és a referenciák után.”

---

## Rövid összefoglaló táblázat (egy diára, ha csak egy hely van)

| Kérdés | Egy mondatos válasz | Szám |
|---|---|---|
| Hardver vs. licenc | 100 orvosnál a szerver ≈ fél évnyi licenc; kicsiknek megosztott szerver / lízing | 24 M Ft/év licenc vs. ≈ 8–12 M Ft szerver |
| Felhő | telepítés-független; ha a felhő engedett lesz, előfizetésre váltunk | — |
| Visszafejtés | a másolatot az MDR-tanúsítás és a szerződés állítja meg, nem a kód titka | ≈ 110 k EUR, 1–2 év |
| Finomhangolás | igen: desztilláció szintetikus adaton, LoRA a 4B-re, ugyanazzal a méréssel | 4B 0/5 vs. 27B 3/5, 100%-os ismételhetőség |
| Streaming | a várakozást csökkenti, a GPU-időt nem; középút: darabolt ASR | NyM ≈ 290 / 550 s |
| GPU-szerver / DACH | csomag + lízing; V4+RO elsőként, DACH a CE után | SAM ≈ 38 Mrd Ft/év |
