CREATE EXTENSION IF NOT EXISTS "btree_gist";

-- maintains a consistent record ID for equivalent key hash values per model 
-- constraint does not apply when key hash is NULL
CREATE TABLE record_identifiers (
  id                     UUID PRIMARY KEY,
  dataset_id             INT NOT NULL REFERENCES datasets(id),
  model_id_unambiguous   UUID NOT NULL, -- a "model_id" column name causes ambiguous column name errors when used in ON CONFLICT clause
  key_hash               TEXT,
  created_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

  UNIQUE (id, model_id_unambiguous), -- supports foreign key constraint
  UNIQUE (id, dataset_id) -- supports foreign key constraint
);

CREATE UNIQUE INDEX record_identifiers_model_id_key_hash_key
  ON record_identifiers (model_id_unambiguous, key_hash)
  INCLUDE (id)
  WHERE key_hash IS NOT NULL;

CREATE TABLE records (
  -- internally used as row-level primary key and for efficient pagination
  sort_key      BIGSERIAL PRIMARY KEY,

  id            UUID NOT NULL,
  dataset_id    INT NOT NULL REFERENCES datasets(id),

  model_id      UUID NOT NULL,
  model_version INT  NOT NULL,

  -- value has all fields marked sensitive in the model schema removed
  -- value_encrypted includes sensitive fields and is not directly queryable
  -- value_encrypted is NULL when the model version schema has no sensitive fields
  value           JSONB NOT NULL,
  value_encrypted BYTEA NULL,

  -- valid_from should be the same as created_at
  -- valid_to is NULL when is_current is true
  valid_from    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  valid_to      TIMESTAMPTZ,
  is_current    BOOLEAN     NOT NULL DEFAULT true,

  provenance_id UUID NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

  FOREIGN KEY (id, model_id)
      REFERENCES record_identifiers (id, model_id_unambiguous),

  FOREIGN KEY (id, dataset_id)
      REFERENCES record_identifiers (id, dataset_id),

  FOREIGN KEY (model_id, model_version)
      REFERENCES model_versions (model_id, version),

  FOREIGN KEY (model_id, dataset_id)
      REFERENCES models (id, dataset_id),

  CHECK ((is_current AND valid_to IS NULL) OR NOT is_current)
);

-- Ensure only one "current" record (ID) within a model
CREATE UNIQUE INDEX records_id_is_current_key
  ON records(id) WHERE is_current;

-- For efficient pagination of current model records
CREATE INDEX records_model_id_sort_key_is_current_idx
  ON records (model_id, sort_key) WHERE is_current;

-- For efficient temporal queries over model records
CREATE INDEX records_model_id_valid_range_gist_idx
  ON records
  USING gist (
    model_id,
    tstzrange(valid_from, COALESCE(valid_to,'infinity'))
  );

-- For efficient temporal queries over a single logical model record
CREATE INDEX records_id_valid_range_gist_idx
  ON records
  USING gist (
    id,
    tstzrange(valid_from, COALESCE(valid_to, 'infinity'))
  );
