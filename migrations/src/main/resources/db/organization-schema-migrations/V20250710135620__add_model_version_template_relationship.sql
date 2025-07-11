ALTER TABLE model_template_versions
  ADD CONSTRAINT model_template_versions_id_version_schema_hash_key
  UNIQUE (model_template_id, version, schema_hash);

ALTER TABLE model_versions
  ADD COLUMN model_template_id      UUID NULL,
  ADD COLUMN model_template_version INT  NULL;

ALTER TABLE model_versions
  ADD CONSTRAINT model_versions_model_template_id_model_template_version_check
        CHECK ((model_template_id IS NULL AND model_template_version IS NULL) OR
               (model_template_id IS NOT NULL AND model_template_version IS NOT NULL));

ALTER TABLE model_versions
  ADD CONSTRAINT model_versions_model_template_versions_fkey
  FOREIGN KEY (model_template_id, model_template_version, schema_hash)
  REFERENCES model_template_versions (model_template_id, version, schema_hash);
