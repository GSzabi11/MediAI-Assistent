-- Hangprofil felvétele a böngészőből (docs/voice-module.md). A felolvasott mondatokat a Spring
-- fogadja, a hanglenyomatot a Python hangszolgáltatás számolja és callbackkel adja vissza; addig a
-- profil PROCESSING és nem aktív. A V4-es (kézzel, a Python CLI-ből rögzített) sorok READY-k maradnak,
-- de hanglenyomat nélkül nem engednek felvételt (a service ellenőrzi) — újra kell venni őket.
ALTER TABLE voice_profile
    ADD COLUMN status varchar(12) NOT NULL DEFAULT 'READY',
    ADD COLUMN error text,
    ADD COLUMN sentences_used integer,
    ADD COLUMN warnings jsonb NOT NULL DEFAULT '[]'::jsonb,
    ALTER COLUMN embedding_model DROP NOT NULL;

ALTER TABLE voice_profile ALTER COLUMN status DROP DEFAULT;

ALTER TABLE voice_profile
    ADD CONSTRAINT voice_profile_status_values CHECK (status IN ('PROCESSING', 'READY', 'FAILED')),
    -- aktív csak kész profil lehet; kész profilnál ismert a beágyazó modell
    ADD CONSTRAINT voice_profile_active_is_ready CHECK (NOT active OR status = 'READY'),
    ADD CONSTRAINT voice_profile_ready_has_model CHECK (status <> 'READY' OR embedding_model IS NOT NULL),
    ADD CONSTRAINT voice_profile_failed_has_error CHECK (status <> 'FAILED' OR error IS NOT NULL),
    ADD CONSTRAINT voice_profile_sentences_non_negative CHECK (sentences_used >= 0);

-- Felhasználónként egyszerre egy készülő profil
CREATE UNIQUE INDEX voice_profile_one_processing_idx
    ON voice_profile (user_id)
    WHERE status = 'PROCESSING';
