-- Gyógyszer-interakció: a referenciaadat lefedettsége (IDK 2. táblázat 9. sor) — a betöltött helyi táblákból,
-- a betöltés naplójától (drug_reference_import.note) függetlenül újraszámolva. Betegadatot nem olvas.
-- Futtatás: docker exec -i mediai-assistent-db-1 psql -U postgres -d mydatabase < ai-service/eval/ddi_coverage.sql

\echo '== Betöltött referenciaadat'
SELECT source, version_label, row_count, imported_at FROM drug_reference_import ORDER BY id DESC LIMIT 3;

\echo '== Forgalomban lévő ATC-kódok és DDInter-hatóanyaggal rendelkezők'
WITH m AS (SELECT DISTINCT atc_code FROM pupha_product WHERE on_market AND atc_code IS NOT NULL)
SELECT count(*) AS forgalomban_atc,
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM atc_ddi_drug a WHERE a.atc_code = m.atc_code)) AS ddinter_adattal,
       round(100.0 * count(*) FILTER (WHERE EXISTS (SELECT 1 FROM atc_ddi_drug a WHERE a.atc_code = m.atc_code))
             / count(*), 1) AS szazalek
FROM m;

\echo '== Ugyanez termékszinten (forgalomban lévő kiszerelések)'
SELECT count(*) AS forgalomban_termek,
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM atc_ddi_drug a WHERE a.atc_code = p.atc_code)) AS ddinter_adattal,
       round(100.0 * count(*) FILTER (WHERE EXISTS (SELECT 1 FROM atc_ddi_drug a WHERE a.atc_code = p.atc_code))
             / count(*), 1) AS szazalek
FROM pupha_product p WHERE on_market AND atc_code IS NOT NULL;

\echo '== A megfeleltetés forrása (forgalomban lévő ATC-kódokra)'
SELECT a.origin, count(DISTINCT a.atc_code) AS atc_kod
FROM atc_ddi_drug a WHERE a.atc_code IN (SELECT atc_code FROM pupha_product WHERE on_market)
GROUP BY a.origin ORDER BY 2 DESC;

\echo '== Ismert interakciós párok a referenciaadatban (szúrópróba; a súlyosság a DDInter besorolása)'
WITH pairs(a, b) AS (VALUES
  ('Warfarin', 'Amiodarone'), ('Simvastatin', 'Clarithromycin'), ('Sildenafil', 'Nitroglycerin'),
  ('Ramipril', 'Spironolactone'), ('Methotrexate', 'Trimethoprim'), ('Clopidogrel', 'Omeprazole'),
  ('Digoxin', 'Amiodarone'), ('Lithium carbonate', 'Ibuprofen'), ('Tramadol', 'Sertraline'), ('Acenocoumarol', 'Ibuprofen'),
  ('Acetaminophen', 'Amoxicillin')),
d AS (SELECT p.a, p.b, da.id AS ia, db.id AS ib FROM pairs p
      LEFT JOIN ddi_drug da ON lower(da.name) = lower(p.a)
      LEFT JOIN ddi_drug db ON lower(db.name) = lower(p.b))
SELECT d.a, d.b, (d.ia IS NOT NULL AND d.ib IS NOT NULL) AS mindketto_ismert,
       coalesce(i.level, '— nincs adat') AS szint
FROM d LEFT JOIN ddi_interaction i
  ON i.drug_a = least(d.ia, d.ib) AND i.drug_b = greatest(d.ia, d.ib);
