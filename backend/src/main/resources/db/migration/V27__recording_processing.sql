-- Hangfelvétel feldolgozása a Python hangszolgáltatásban (docs/voice-module.md): a böngésző a
-- felvétel végén feltölti a hangot, a Python átírja (Whisper), szétválasztja a beszélőket
-- (pyannote), szerepet oszt az orvos hangprofilja alapján, és a MedGemma átnézi az átiratot.
ALTER TABLE recording
    ADD COLUMN processing_error text,
    ADD COLUMN processing_warnings jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN processed_at timestamptz,
    -- a nyers hang a feldolgozás után törlődik (betegadat); az audio_path a nyilvántartásban marad
    ADD COLUMN audio_deleted_at timestamptz,
    -- a MedGemma-átnézés modellje (null: nem futott / nem volt elérhető)
    ADD COLUMN review_model varchar(128),
    ADD CONSTRAINT recording_failed_has_error CHECK (status <> 'FAILED' OR processing_error IS NOT NULL);

-- AI-javítás megszólalásonként. A megszólalás továbbra is egyszer, egyben töltődik be és nem
-- módosítható: az ASR eredeti szövege / beszélője mellé kerül a javított, a provenance megmarad.
-- text / speaker = a megjelenített (esetleg javított) változat; asr_* = az eredeti, ha eltér.
ALTER TABLE utterance
    ADD COLUMN asr_text text,
    ADD COLUMN asr_speaker varchar(12),
    ADD COLUMN corrected_by varchar(128),
    ADD COLUMN correction_reason text,
    -- az AI megjegyzése módosítás nélkül (pl. echo: „az érték valószínűtlen, ellenőrizendő”)
    ADD COLUMN review_note text,
    ADD CONSTRAINT utterance_asr_speaker_values CHECK (asr_speaker IN ('DOCTOR', 'PATIENT', 'OTHER')),
    -- javított megszólalásnál ismert a javító modell és az eredeti; javítatlannál egyik sincs
    ADD CONSTRAINT utterance_correction_complete CHECK (
        (corrected_by IS NULL AND asr_text IS NULL AND asr_speaker IS NULL AND correction_reason IS NULL)
        OR (corrected_by IS NOT NULL AND (asr_text IS NOT NULL OR asr_speaker IS NOT NULL))
    );
