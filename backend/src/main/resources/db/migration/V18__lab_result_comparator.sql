-- Cenzorált laboreredmény (pl. EGFR-EPI ">90", CRP "<0,5"): az FHIR Quantity mintája szerint
-- érték + összehasonlító jel. A value_num a határérték (90), a comparator mondja meg, hogy a
-- valódi érték azon túl van — így a szám nem tűnik pontos mérésnek, és nem kerekítünk csendben.
-- NULL = pontos érték. Kvalitatív eredmény ("negatív") továbbra sem kerülhet be.
ALTER TABLE lab_result ADD COLUMN comparator varchar(2);

ALTER TABLE lab_result
    ADD CONSTRAINT comparator_values CHECK (comparator IS NULL OR comparator IN ('<', '<=', '>', '>='));
