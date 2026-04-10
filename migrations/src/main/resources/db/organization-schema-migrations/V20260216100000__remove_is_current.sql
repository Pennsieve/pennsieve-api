DROP INDEX IF EXISTS records_id_is_current_key;
CREATE UNIQUE INDEX records_id_valid_to_is_null_key ON records(id) WHERE valid_to IS NULL;

DROP INDEX IF EXISTS records_model_id_sort_key_is_current_idx;
CREATE INDEX records_model_id_sort_key_valid_to_is_null_idx ON records(model_id, sort_key) WHERE valid_to IS NULL;

DROP INDEX IF EXISTS records_model_id_key_hash_key;
CREATE UNIQUE INDEX records_model_id_key_hash_key ON records(model_id, key_hash)
    WHERE key_hash IS NOT NULL AND valid_to IS NULL;

ALTER TABLE records DROP CONSTRAINT IF EXISTS records_check;
ALTER TABLE records DROP COLUMN is_current;
