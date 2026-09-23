-- A V5-ben a recorded_at default és NOT NULL nélkül jött létre, ezért az addig
-- beszúrt sorok NULL-t kaptak. Ezek pontos időpontja nem állítható vissza; csak
-- fejlesztői tesztadat lehet, a migráció futásának idejét kapják.
UPDATE recording SET recorded_at = now() WHERE recorded_at IS NULL;

ALTER TABLE recording ALTER COLUMN recorded_at SET DEFAULT now();
ALTER TABLE recording ALTER COLUMN recorded_at SET NOT NULL;
