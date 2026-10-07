-- Automatikus dokumentum-feldolgozás (Spring → Python AI-szolgáltatás → callback).
-- A feltöltött PDF típusát a Python sorolja be, ezért feltöltéskor még nincs típus.

ALTER TABLE document ALTER COLUMN type DROP NOT NULL;

-- A meglévő (kézzel feltöltött, típussal rendelkező) sorok feldolgozottnak számítanak
ALTER TABLE document ADD COLUMN status varchar(16) not null default 'PROCESSED';
ALTER TABLE document ALTER COLUMN status DROP DEFAULT;

ALTER TABLE document
    -- A feldolgozás befejezésének (sikeres vagy hibás) DB-ideje
    ADD COLUMN processed_at timestamptz,
    -- FAILED esetén az ok, olvasható szövegként
    ADD COLUMN processing_error text,
    -- Amit a feldolgozás tudatosan kihagyott (pl. cenzorált laborérték, meg nem talált idézet),
    -- JSON szövegtömb — a kihagyás nem lehet csendes
    ADD COLUMN processing_warnings jsonb,
    -- HOGY: melyik modell, milyen prompttal nyerte ki a dokumentum adatait (a laborsoroknak
    -- nincs saját állításuk, a provenance-uk ez + lab_result.document_id)
    ADD COLUMN extraction_model varchar(128),
    ADD COLUMN extraction_seed bigint,
    ADD COLUMN extraction_prompt_hash varchar(64),
    ADD COLUMN extractor_version varchar(64);

ALTER TABLE document
    ADD CONSTRAINT status_values CHECK (status IN ('PROCESSING', 'PROCESSED', 'FAILED')),
    -- Feldolgozott dokumentumnak mindig van típusa
    ADD CONSTRAINT processed_has_type CHECK (status <> 'PROCESSED' OR type IS NOT NULL),
    ADD CONSTRAINT failed_has_error CHECK (status <> 'FAILED' OR processing_error IS NOT NULL),
    ADD CONSTRAINT processing_not_finished CHECK (status <> 'PROCESSING' OR processed_at IS NULL),
    ADD CONSTRAINT warnings_is_array CHECK (processing_warnings IS NULL
                                            OR jsonb_typeof(processing_warnings) = 'array');

-- A sorban álló / beragadt feldolgozások gyors listázásához
CREATE INDEX document_processing_idx ON document (status) WHERE status = 'PROCESSING';

-- Gyógyszer és allergia eredete: a pipeline a jóváhagyás előtt, "nem ellenőrzött"
-- tételként írja be (a forrás-állítás ekkor még PENDING). Az ellenőrzöttség nem tárolt
-- flag (elavulna), hanem a forrás-állítás legfrissebb döntéséből számolódik.
ALTER TABLE medication ADD COLUMN origin varchar(32) not null default 'MANUAL';
ALTER TABLE medication ALTER COLUMN origin DROP DEFAULT;
ALTER TABLE medication
    ADD CONSTRAINT medication_origin_values CHECK (origin IN ('MANUAL', 'DOCUMENT_PIPELINE')),
    ADD CONSTRAINT medication_pipeline_has_source CHECK (origin <> 'DOCUMENT_PIPELINE'
                                                          OR source_statement_id IS NOT NULL);

ALTER TABLE allergy ADD COLUMN origin varchar(32) not null default 'MANUAL';
ALTER TABLE allergy ALTER COLUMN origin DROP DEFAULT;
ALTER TABLE allergy
    ADD CONSTRAINT allergy_origin_values CHECK (origin IN ('MANUAL', 'DOCUMENT_PIPELINE')),
    ADD CONSTRAINT allergy_pipeline_has_source CHECK (origin <> 'DOCUMENT_PIPELINE'
                                                       OR source_statement_id IS NOT NULL);
