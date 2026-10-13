-- A felvétel-összefoglaló orvosi szerkesztése. Az AI eredeti szövege (summary, points) NEM változik —
-- a modell kimenete és az orvos javítása is visszakereshető (mint a statement_approval MODIFIED-nál).
-- A megjelenített szöveg: edited_text, ha van, különben summary. Újraszerkesztés felülírja az előzőt
-- (az előzmény nem kell: az eredeti AI-szöveg megmarad, újragenerálás új sort ad).
ALTER TABLE recording_summary
    ADD COLUMN edited_text text,
    ADD COLUMN edited_by BIGINT REFERENCES app_user (id) ON DELETE RESTRICT,
    ADD COLUMN edited_at timestamptz,
    ADD CONSTRAINT recording_summary_edit_complete CHECK (
        (edited_text IS NULL AND edited_by IS NULL AND edited_at IS NULL)
        OR (edited_text IS NOT NULL AND btrim(edited_text) <> '' AND edited_by IS NOT NULL AND edited_at IS NOT NULL)
    ),
    ADD CONSTRAINT recording_summary_edit_only_done CHECK (edited_text IS NULL OR status = 'DONE');
