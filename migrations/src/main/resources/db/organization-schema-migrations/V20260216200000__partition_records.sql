-- Partition the records table into current (valid_to IS NULL) and history (valid_to IS NOT NULL).
-- This physically separates hot current-record data from cold historical versions,
-- enabling partition pruning for the dominant current-record query pattern.

-- Step 1: Drop triggers that reference the records table
DROP TRIGGER IF EXISTS cascade_delete_record_packages_trigger ON records;
DROP TRIGGER IF EXISTS cascade_delete_relationships_trigger ON records;

-- Step 2: Drop foreign keys pointing to records (from triggers replaced by functions)
-- No FK references records directly since drop_record_identifiers migration

-- Step 3: Rename existing table and its sequence
ALTER TABLE records RENAME TO records_old;
ALTER SEQUENCE records_sort_key_seq RENAME TO records_old_sort_key_seq;

-- Step 4: Create partitioned table (no PRIMARY KEY on the parent; each partition gets local indexes)
-- Column order must match records_old so that INSERT INTO records SELECT * FROM records_old works.
CREATE TABLE records (
    sort_key        BIGINT NOT NULL DEFAULT nextval('records_old_sort_key_seq'),
    id              UUID NOT NULL,
    dataset_id      INT NOT NULL,
    model_id        UUID NOT NULL,
    model_version   INT NOT NULL,
    value           JSONB NOT NULL,
    value_encrypted BYTEA NULL,
    valid_from      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to        TIMESTAMPTZ,
    provenance_id   UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    key_hash        TEXT
) PARTITION BY LIST ((valid_to IS NULL));

-- Step 5: Create partitions
CREATE TABLE records_current PARTITION OF records
    FOR VALUES IN (true);

CREATE TABLE records_history PARTITION OF records
    FOR VALUES IN (false);

-- Step 6: Indexes on current partition (hot path)
CREATE UNIQUE INDEX records_current_sort_key_key
    ON records_current(sort_key);

CREATE UNIQUE INDEX records_current_id_key
    ON records_current(id);

CREATE INDEX records_current_model_sort_key_idx
    ON records_current(model_id, sort_key);

CREATE UNIQUE INDEX records_current_model_key_hash_key
    ON records_current(model_id, key_hash)
    WHERE key_hash IS NOT NULL;

CREATE INDEX records_current_dataset_model_idx
    ON records_current(dataset_id, model_id);

-- Step 7: Indexes on history partition (cold path)
CREATE UNIQUE INDEX records_history_sort_key_key
    ON records_history(sort_key);

CREATE INDEX records_history_id_sort_key_desc_idx
    ON records_history(id, sort_key DESC);

CREATE INDEX records_history_id_dataset_idx
    ON records_history(id, dataset_id);

-- GIST indexes for as_of temporal queries (only on history, no write overhead on hot path)
CREATE INDEX records_history_model_valid_range_gist_idx
    ON records_history USING gist (
        model_id,
        tstzrange(valid_from, valid_to)
    );

CREATE INDEX records_history_id_valid_range_gist_idx
    ON records_history USING gist (
        id,
        tstzrange(valid_from, valid_to)
    );

-- Step 8: Migrate data from old table
-- PostgreSQL automatically moves rows between partitions when the partition key
-- expression changes (e.g., UPDATE sets valid_to from NULL to a timestamp).
INSERT INTO records SELECT * FROM records_old;

-- Step 10: Reassign sequence ownership
ALTER SEQUENCE records_old_sort_key_seq RENAME TO records_sort_key_seq;
ALTER SEQUENCE records_sort_key_seq OWNED BY records.sort_key;

-- Step 11: Add foreign key constraints on parent table
ALTER TABLE records ADD CONSTRAINT records_dataset_id_fkey
    FOREIGN KEY (dataset_id) REFERENCES datasets(id) ON DELETE CASCADE;

ALTER TABLE records ADD CONSTRAINT records_model_id_model_version_fkey
    FOREIGN KEY (model_id, model_version) REFERENCES model_versions(model_id, version) ON DELETE CASCADE;

ALTER TABLE records ADD CONSTRAINT records_model_id_dataset_id_fkey
    FOREIGN KEY (model_id, dataset_id) REFERENCES models(id, dataset_id) ON DELETE CASCADE;

-- Step 12: Recreate triggers for cascade deletes
CREATE TRIGGER cascade_delete_record_packages_trigger
AFTER DELETE ON records
FOR EACH ROW EXECUTE FUNCTION cascade_delete_record_packages();

CREATE TRIGGER cascade_delete_relationships_trigger
AFTER DELETE ON records
FOR EACH ROW EXECUTE FUNCTION cascade_delete_relationships();

-- Step 13: Drop old table
DROP TABLE records_old;
