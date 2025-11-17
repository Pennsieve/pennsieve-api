ALTER TABLE models
    ADD COLUMN deleted_at TIMESTAMPTZ DEFAULT NULL;

CREATE INDEX models_deleted_at_idx
    ON models(deleted_at)
    WHERE deleted_at IS NULL;

ALTER TABLE model_versions
    ADD COLUMN deleted_at TIMESTAMPTZ DEFAULT NULL;

CREATE INDEX model_versions_deleted_at_idx
    ON model_versions(deleted_at)
    WHERE deleted_at IS NULL;
