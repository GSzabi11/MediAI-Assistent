# A bemutató átnézése — MediAI_IDK_2026_eloadas_v2.pptx (2026-11-15 19:55-ös mentés)

22 dia: 18 fő dia + 4 tartalék (19–22.) a „Köszönöm” után. A pptx-et nem módosítottam.

## 1. A bírálat pontjai — szerepel-e a javítás

| Bírálói pont | Hol | Állapot |
|---|---|---|
| TRL 4: a valós leleteken végzett próbáról nincs számszerű eredmény | 9. dia (27 lelet, 99,0%, 92,6%, 34 s), 11. dia (TRL 4 sor) | ✔ |
| A két újdonság (forrásidézet-elutasítás, riasztás-visszavonás) nincs igazolva | 9. dia, táblázat (30/30, 30/30, 5/5, 10/10, 10/10) | ✔ |
| A hangátnézés nem determinisztikus | 8. dia (100% egyezés 3 seed × 3 futás), 20. dia | ✔ (ld. 2.3) |
| A hibakezelés nincs tesztelve | 10. dia (3 leállítás, helyreállási idő) | ✔ |
| Kitalált adat, kis minta | 9. dia (valós leletek, 1831 sor) | ✔ |
| WER/DER-mérés nincs | 8. dia (WER) | ⚠ a WER-szám elavult, DER nincs (ld. 2.1, 2.2) |
| Demó és forráskód hiányzik | 4. és 18. dia: GitHub-QR kész, a demó QR helye jelölve | ⚠ a demó QR-t még pótolni kell |
| Riasztási fáradtság, az orvos terhe | 10. dia (1077 állítás, 98,1% döntés nélkül, kontrollfelület) | ✔ |
| Iparjogvédelem: szabadalom vs. üzleti titok, igénypontok, know-how, védjegy | 15. dia (táblázat, igénypont-vázlat, know-how, védjegy osztályokkal és díjjal) | ✔ |
| Csak GPU-oldali skálázás; a 48 GB feltételezés; a tárhely hiányzik | 11. dia (2 GPU, „feltételezés, szerveren mérendő”; tárhely 0,4 TB/év, mérve) | ✔ |
| A licenccsere költsége nincs a pénzügyi tervben | 16. dia (Licenc: „díja tárgyalás után, megegyezés alapján”) | ✔ (Szabi döntése szerint) |
| A TAM 20% és a SAM 50% eltérése nincs indokolva | 12. dia (külön indoklás) | ✔ |

## 2. A 6 kérdés — tartalékdiák

| Kérdés | Dia | Állapot |
|---|---|---|
| Hardver vs. licencdíj, felhő, visszafejtés | 19. | ✔ |
| Kisebb modell finomhangolása | 20. | ✔ (a WER-szám elavult) |
| Teljes felvétel vs. streaming | 21. | ✔ |
| GPU-szerver, csomag, V4 vs. DACH | 22. | ✔ (a csomag és a DACH csak itt, a pénzügyi terv nem számol vele — a kérés szerint) |

### Javítandó / ellenőrizendő

2.1 **A WER-szám elavult** (a második felolvasás előtti állapot):
- 8. dia, kártya: „18,0% … 5 felolvasott szöveg, 419 szó; írásmódtól függetlenül 13,6%” → javaslat: „18–21% … 2 × 5 felolvasott
  szöveg, 838 szó; írásmódtól függetlenül 14–16%”;
- a 8. dia jegyzetében: „18,0% … 13,6% … (31%)” → „18–21% … 14–16% … (24–31%)”;
- 20. dia, „Megismételhetőség”: „szóhibaaránya 18,0%” → „18–21%”.

2.2 **DER:** a bírálat „WER/DER-mérés nincs” pontjára a DER-ről sehol nincs szó. Egy mondat elég (pl. a 8. dia jegyzetében
vagy lábjegyzetében): „DER-t nem mértem: a WER-felvételek egybeszélős echo-diktálások; a következő lépés kétbeszélős
felolvasás.”

2.3 **A hangátnézés determinizmusa**, 8. dia jegyzete: „az eredmények azonosak lettek, a hangátnézés is, miután rögzített
seedet kapott”. Pontosabb: a hangátnézés **a seed nélkül is** ugyanazt adta (pontosan egyezett a 10-27-i méréssel), mert
`temperature: 0`. A seed most már rögzíthető, de a determinizmust nem ez hozta. A kártyán a „(rögzített seed)” igaz.
A 27B-nél seedenként 1 futás volt (3 × 1), ezt a jegyzetbe érdemes beírni, ha rákérdeznek.

2.4 **9. dia, kártya:** „a maradék 14 hiba: nem futó szolgáltatás”. Pontosan: 10 nem futó feldolgozó szolgáltatás + 4
Ollama-hívás hiba. Javaslat: „nem futó szolgáltatás vagy modellfuttató”. A jegyzetben „egy mentési hibát (ismétlődő
gyógyszernév) javítottam” helyett pontosabb: „egy már javított hiba (ismétlődő szó a gyógyszer nevében) miatt bukott
6 futást újrafuttattam, és regressziós tesztet írtam rá”.

2.5 **19. dia, „Visszafejtés”:** az üzleti titok irányelvre (2016/943/EU) való hivatkozás helyes. Szoftvernél azonban a
szoftverirányelv (2009/24/EK) is számít: a program működésének megfigyelése szerződéssel sem tiltható; a visszafordítás
csak együttműködő-képesség céljából megengedett, és az így kapott kód versenytermékbe nem építhető. Ha rákérdeznek,
ez erősíti a választ.

2.6 **A demó QR helye** a 4. és a 18. dián még sárga helyőrző; Szabi szerint utólag pótolja.

## 3. Megfelelés az előadói sablonnak (IDK_eloadoi_sablon_2026)

| Sablondia | A bemutatóban | Állapot |
|---|---|---|
| Cím, név, program, kar, konzulens és tanszék | 1. | ✔ |
| A megoldandó probléma (miért fontos, a technika állása, miért kell új megközelítés) | 2–3. | ✔ |
| A megoldás ismertetése (képek, demonstráció, válaszol-e a problémára, ipari alkalmazhatóság) | 4–11. (7. képernyők, 8–10. mérések, 11. ipari alkalmazhatóság) | ✔ |
| Üzleti potenciál és piacelemzés (igény, vevőkör, méret, trend, kapcsolatfelvétel) | 12–13. | ✔ |
| Megvalósíthatóság és üzleti terv (piacra vitel, hasznosítási útvonal) | 14–16. | ✔ |
| A csapat ismertetése | 17. | ✔ |
| Köszönöm a figyelmet | 18. | ✔ |

- A sablon arculata (bordó cím, vonal, BME-logó és diaszám a láblécben, a záró dia) mindenhol egyezik; a diaszámok
  mezőként helyesek (1–22). A tartalékdiák a záró dia után vannak, ez így helyes.
- **Hossz:** 18 fő dia. Ha az előadásidő 10 perc, az diánként kb. 33 mp. Ha a keret szűk, a 6. (felépítés) és a 7.
  (képernyők) összevonható, vagy a 13. rövidíthető.
- **Olvashatóság:** több dián sok a 10–11 pt-os szöveg: 6. dia (a szöveg 54%-a 12 pt alatt, architektúraábra), 8. dia
  (31%), 16. dia (29%), 15. dia (sűrű: 1610 karakter). Kivetítve ezek nehezen olvashatók; a 15. és a 19. dia szövegét
  érdemes rövidíteni, vagy a részleteket a jegyzetbe tenni.
