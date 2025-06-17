CREATE TABLE IF NOT EXISTS models (
  id UUID NOT NULL PRIMARY KEY,
  dataset_id INT NOT NULL REFERENCES datasets(id),
  name TEXT NOT NULL,
  display_name TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (id, dataset_id) -- for referential integrity with records
);

CREATE UNIQUE INDEX models_dataset_id_name_key ON models (dataset_id, LOWER(name));

CREATE TRIGGER model_update_updated_at BEFORE UPDATE ON models FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

CREATE TABLE IF NOT EXISTS model_versions (
  model_id UUID NOT NULL REFERENCES models(id),
  version INT NOT NULL CHECK (version > 0) DEFAULT 1,
  schema JSONB NOT NULL,
  schema_hash TEXT NOT NULL ,
  key_properties TEXT[] NOT NULL,
  sensitive_properties TEXT[] NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (model_id, version)
);

CREATE TABLE IF NOT EXISTS model_templates (
  id UUID NOT NULL PRIMARY KEY,
  name TEXT NOT NULL,
  display_name TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX model_templates_dataset_id_name_key ON model_templates (LOWER(name));

CREATE TRIGGER model_template_update_updated_at BEFORE UPDATE ON model_templates FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

CREATE TABLE IF NOT EXISTS model_template_versions (
  model_template_id UUID NOT NULL REFERENCES model_templates(id),
  version INT NOT NULL CHECK (version > 0) DEFAULT 1,
  schema JSONB NOT NULL,
  schema_hash TEXT NOT NULL ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (model_template_id, version)
);
