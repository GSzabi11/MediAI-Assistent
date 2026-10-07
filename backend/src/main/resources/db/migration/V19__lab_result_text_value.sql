-- Kvalitatív laboreredmény (pl. vizelet: Fehérje "Negatív", Nyák "kevés"): szó szerinti szöveg.
-- A value_num NEM lett szöveg: a trend, a grafikon és a (majdani) szabálymotorok számot
-- várnak. Egy sorban pontosan az egyik van: szám (value_num, + comparator) VAGY szöveg.
ALTER TABLE lab_result ALTER COLUMN value_num DROP NOT NULL;

ALTER TABLE lab_result
    ADD COLUMN value_text varchar(64),
    -- Szöveges referencia ("Negatív", "normális"); a számos tartomány továbbra is ref_low/ref_high
    ADD COLUMN ref_text varchar(128);

ALTER TABLE lab_result
    ADD CONSTRAINT value_num_xor_text CHECK ((value_num IS NULL) <> (value_text IS NULL)),
    ADD CONSTRAINT value_text_not_blank CHECK (value_text IS NULL OR btrim(value_text) <> ''),
    ADD CONSTRAINT ref_text_not_blank CHECK (ref_text IS NULL OR btrim(ref_text) <> ''),
    -- Összehasonlító jel és számos tartomány csak számos eredményhez
    ADD CONSTRAINT comparator_needs_number CHECK (comparator IS NULL OR value_num IS NOT NULL),
    ADD CONSTRAINT text_value_has_no_numeric_ref CHECK (value_text IS NULL OR (ref_low IS NULL AND ref_high IS NULL));
