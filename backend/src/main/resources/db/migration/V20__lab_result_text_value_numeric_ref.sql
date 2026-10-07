-- A V19 túl szigorú volt: szöveges eredményhez is tartozhat számos referencia-tartomány.
-- Pl. "Vizelet mikroalbumin 24h ürítés" = "Nem számolható", referencia "< 30.0 mg/nap" — a
-- tartomány a vizsgálat ténye, akkor is érvényes, ha az adott eredmény nem szám.
ALTER TABLE lab_result DROP CONSTRAINT text_value_has_no_numeric_ref;
