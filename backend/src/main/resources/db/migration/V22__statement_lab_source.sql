-- Első determinisztikus szabálymotor: laborérték a referenciatartományon kívül → DETERMINISTIC_RULE
-- állítás. MERRE: a konkrét laborsor (LAB {labResultId}). LAB forrás csak szabály-állításé lehet
-- (egy LLM-kimenet ne hivatkozhasson úgy, mintha szabály számolta volna).
ALTER TABLE statement DROP CONSTRAINT source_type_values;
ALTER TABLE statement
    ADD CONSTRAINT source_type_values CHECK (source ->> 'type' IN ('AUDIO', 'DOCUMENT', 'IMAGE', 'LAB')),
    ADD CONSTRAINT lab_source_only_for_rules CHECK (source ->> 'type' <> 'LAB' OR type = 'DETERMINISTIC_RULE');

-- Egy mérésről egy szabály legfeljebb egy állítást ad: az újrafuttatás (indulási pótlás,
-- minden labormentés) idempotens, DB-szinten kikényszerítve
CREATE UNIQUE INDEX statement_rule_lab_result_idx
    ON statement ((producer ->> 'ruleId'), ((source ->> 'labResultId')::bigint))
    WHERE source ->> 'type' = 'LAB';
