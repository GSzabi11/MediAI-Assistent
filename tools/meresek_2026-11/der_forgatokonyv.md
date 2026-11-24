# DER-mérés — kétszereplős forgatókönyv (D1, ambuláns vizit)

> Kitalált eset, kitalált beteg (nem valós személy): kardiológiai ambuláns kontrollvizit, a beteg hazamegy. Felolvasás: **O = orvos** (annak kell olvasnia, akinek az aktív
> hangprofilja van az alkalmazásban), **B = beteg**. Hossza ≈ 540 szó, természetes tempóban ≈ 4–5 perc, 75 fő megszólalás.
>
> Jelölések felolvasáshoz:
>
> - `‹B: …›` egy **O**-sor belsejében (vagy `‹O: …›` egy B-sorban): a másik szereplő **ott, belevágva** mondja, a fő
>   beszélő **nem áll meg**, folytatja a mondatát → valódi átfedés (összesen 7 ilyen hely).
> - `(…)` csak rendezői utasítás, nem kell felolvasni.
> - A rövid válaszokat (`Igen.`, `Nem.`) gyorsan, szünet nélkül mondjátok — ezek a nehéz esetek a diarizációnak.
>
> A `der.py` ebből olvassa a fő megszólalások várt sorrendjét (O/B), a `‹…›` betéteket átfedésként külön kezeli.
> A sorok elején álló sorszámot (O01, B02 …) ne olvassátok fel.

## Forgatókönyv

O01: Jó napot kívánok! Jöjjön be, foglaljon helyet, kérem. Hogy szólíthatom?
B02: Jó napot! Németh Ilona vagyok.
O03: Hány éves, Ilona?
B04: Hatvannégy.
O05: Rendben. Kontrollra jött, ugye? Hogy van mostanában?
B06: Hát, doktor úr, egyre nehezebben kapok levegőt. Már a lépcsőn is meg kell állnom a második emeletnél, ‹O: Aha.› pedig régebben fel se tűnt, hogy lépcsőzöm. És a múlt héten egyik éjjel fel kellett ülnöm az ágyban, mert fekve nem ment a levegővétel.
O07: Mióta tart ez?
B08: Körülbelül három hete. Talán egy hónapja.
O09: Fokozatosan jött, vagy hirtelen?
B10: Fokozatosan. Hétről hétre egy kicsit rosszabb.
O11: Mellkasi fájdalma van?
B12: Nincs.
O13: Szorítás, nyomás a mellkasban?
B14: Nem, az nincs. Inkább olyan, mintha nem férne be a levegő.
O15: Értem. Köhög?
B16: Igen, főleg éjszaka. Száraz köhögés.
O17: Láza volt?
B18: Nem volt.
O19: Hány párnával alszik?
B20: Most már kettővel, úgy jobb.
O21: És a lába, nem dagadt meg az utóbbi időben, ‹B: Dehogynem!› mondjuk estére?
B22: Mindkét bokám. Estére szorít a cipő.
O23: Változott a testsúlya?
B24: Felhíztam. A mérleg szerint két kilót két hét alatt, pedig nem eszem többet.
O25: Ez fontos adat. És a vizelet mennyisége?
B26: Talán kevesebb, nem figyeltem.
O27: A betegségeit ismerem a papírjaiból: magas vérnyomás, cukorbetegség, és két éve a szívinfarktus. Azóta volt kórházban?
B28: Nem, azóta nem. Csak a háziorvosnál voltam a receptekért.
O29: Melyik gyógyszertárban váltja ki?
B30: A lakásunk melletti patikában.
O31: Milyen gyógyszereket szed most?
B32: Várjon, elhoztam a listát. Metoprolol ötven milligramm reggel és este. Ramipril öt milligramm reggel. Atorvastatin negyven milligramm este. Metformin ezer milligramm, kétszer. És acetilszalicilsav, száz milligramm.
O33: Vízhajtó?
B34: Az nincs.
O35: Mindet rendszeresen szedi?
B36: Igen. Illetve… ‹O: Illetve?› a múlt hónapban pár napig nem, mert elfogyott a ramipril.
O37: Hány napig maradt ki?
B38: Három-négy napig.
O39: Rendben, ezt felírom. Gyógyszerallergiája van?
B40: Igen, a penicillinre. Kiütéses lettem tőle.
O41: Más allergia?
B42: Nincs.
O43: Dohányzik?
B44: Leszoktam, öt éve.
O45: Alkohol?
B46: Ünnepekkor egy pohár bor.
O47: Rendben. Most megvizsgálom. Kérem, üljön fel a vizsgálóágyra.
B48: Ide?
O49: Igen, oda. Vegyen mély levegőt… és fújja ki. Még egyszer.
B50: Így jó?
O51: Pontosan. A tüdő alsó részén mindkét oldalon finom szörtyzörejt hallok. A szívhangok tiszták, ritmusosak, a frekvencia nyolcvannyolc. A vérnyomás százötvenkettő per kilencven, a szaturáció kilencvenöt százalék.
B52: Az rossz?
O53: Nem rossz, de figyelni kell. Mindkét bokán benyomható ödéma van.
B54: Ez mit jelent, doktor úr?
O55: Azt, hogy a szíve most nem bírja elég jól a terhelést, és a szervezetében víz gyűlt fel. Ezt szívelégtelenségnek hívjuk. ‹B: Jaj, istenem.› Ne ijedjen meg, ez jól kezelhető, otthonról is.
B56: Nem kell befeküdnöm?
O57: Most nem. Gyógyszerrel kezdjük, és két hét múlva visszajön.
B58: Mert otthon a férjem egyedül van, és ‹O: Megértem.› ő magától még főzni se tud.
O59: Ezért is jó, hogy otthon maradhat. De ha rosszabb lesz, ne várjon.
B60: Jó. Jó, rendben.
O61: Most a következő lesz. Ma vérvétel: vérkép, ionok, vesefunkció és NT-proBNP. Készül EKG, és kérek szívultrahangot, arra időpontot kap a pultnál.
B62: Fájni fog?
O63: Csak a vérvétel szúr egy kicsit. A többi nem fáj.
B64: Jó.
O65: Kap egy vízhajtót, furoszemidet, negyven milligrammot reggel. Ettől délelőtt többet fog pisilni, ezt ne ijedjen meg.
B66: Értem.
O67: A ramiprilt ne hagyja ki többet. Ha fogyóban van, ‹B: Igen, igen.› szóljon időben a háziorvosnak.
B68: Megígérem.
O69: Van még kérdése?
B70: Mit ehetek? Mire figyeljek?
O71: Keveset sózzon, és a folyadékot napi másfél literre korlátozzuk. Reggelente mérje meg a súlyát, és írja fel.
B72: Másfél liter. Rendben.
O73: Ha három nap alatt két kilónál többet hízik, vagy nyugalomban is fullad, azonnal jöjjön be, vagy hívja a mentőt.
B74: Köszönöm szépen, doktor úr.
O75: Szívesen. ‹B: Viszontlátásra.› Két hét múlva találkozunk!
