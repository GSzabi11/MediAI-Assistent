# Architektúra-döntések

## Alapelvek

1. **On-premise, nincs felhős LLM-hívás betegadattal.** GDPR + EESZT
   szabályozás miatt kizárólag lokálisan futtatott, nyílt súlyú modellek.
2. **Human-in-the-loop.** A rendszer szintetizál és kockázatot jelez; a
   diagnózis és a végső döntés mindig az orvosé. Ez nem UX-döntés, hanem
   az egész `allitas`/`allitas_jovahagyas` adatmodell oka.
3. **Determinisztikus, ahol lehet; LLM csak ahol muszáj.** Referencia-
   tartomány, trend, gyógyszer-interakció, BNO-kódolás: szabály-motor, nem
   modell. Az LLM a szintézisre és a chatre marad.
4. **Explainability = a rendszer legfontosabb nem-funkcionális
   követelménye**, nem utólagos feature. Ezért van minden kimenet
   `allitas`-ként modellezve, forrás-visszakereséssel egészen a karakter-
   pozícióig.

## Rétegek (célarchitektúra, fokozatosan épül)

```
Bevitel (EESZT import, dokumentum feltöltés, PACS, hangfelvétel)
  → Identitás-kapu (patient_id ellenőrzés, eltérés = hard fail)
  → Determinisztikus motorok (referencia-tartomány, trend, gyógyszer, BNO)
  → Szakorvos ágensek (radiológia, labor-kinyerés — később onkológia, kardio stb.)
  → Bizonyíték/RAG (magyar szakmai irányelvek, BGE-M3, pgvector)
  → Szintézis LLM (MedGemma vagy erősebb generalista — ld. lent)
  → Biztonsági kapu (claim_type címkézés, bizonytalanság-jelzés)
  → Megjelenítés / Write-back (aláírás kötelező, AI sosem szignál)
```

Jelenlegi fázis: **csak a `patient` modul + Flyway-séma létezik.** A fenti
a célkép, nem a jelenlegi állapot — ne feltételezd meglévőnek egyik motort
sem, amíg nincs a repóban.

## Modellválasztás — rövid összegzés

- **MedGemma 4B**: fut a fejlesztői laptopon (RTX 5060, 8GB VRAM). Q4/Q5
  kvantálva. Ez a reális helyi fejlesztési alapértelmezés.
- **MedGemma 27B**: NEM fér el 8GB VRAM-ban. Csak szerver-oldali fázisban
  releváns.
- A MedGemma erős multimodális/kinyerési feladatokra (labor→JSON,
  radiológiai lelet), de gyengébb komplex, többlépéses klinikai
  érvelésben (kardiológia, MAST-benchmark) a legjobb zárt termékekhez
  (AMBOSS LiSA stb.) képest — azok viszont felhősek, nem alkalmazhatók.
- Kardiológiai/EKG jel-alapú diagnózisra jelenleg egyik nyílt modell sem
  megbízható (ld. ECG-Reasoning-Benchmark) — ha idáig eljutunk, a nyers
  jelből determinisztikus jelfeldolgozó (szegmentáló) réteg kell az LLM elé,
  ne várjunk grounding-ot közvetlenül a modelltől.
- Ha a szintézis-réteg később szerverre kerül: gpt-oss-120b vagy
  Baichuan-M2-32B jobb HealthBench-eredményt ad, mint a MedGemma 27B.

## MVP-szűkítés (amit a konzulens jóváhagyott)

Első kör: 1 szintetikus EESZT-szerű adatforrás → magyar RAG (BGE-M3 +
irányelvek) → MedGemma szintézis/chat → gyógyszerkölcsönhatás-modul
(RxNorm + DDInter 2.0, determinisztikus). Képalkotó ágens és teljes
fordító-pipeline későbbi fázis.

## Nyelv: a fordítás nem alapértelmezett megoldás

MedGemma (Gemma 3 alapú) megőrzi a magyar nyelvi képességet. NE építs
teljes HU↔EN fordító-chatréteget alapból — az kutatásilag dokumentáltan
elveszítheti a helyi klinikai nuánszot (ld. Med-CoReasoner, 2026). Ha
mégis kell fordítás (pl. angol RAG-forrás bevonásához), célzott legyen,
kis dedikált MT-modellel (Opus-MT), NEM LLM-mel, és gyógyszernév/dózis/
tagadás védett span-ként kezelve, sose szabadon fordítva.
