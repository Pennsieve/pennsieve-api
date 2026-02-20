ALTER TABLE model_templates ADD COLUMN deleted_at TIMESTAMPTZ DEFAULT NULL;
CREATE INDEX model_templates_deleted_at_idx ON model_templates(deleted_at) WHERE deleted_at IS NULL;

ALTER TABLE model_template_versions ADD COLUMN deleted_at TIMESTAMPTZ DEFAULT NULL;
CREATE INDEX model_template_versions_deleted_at_idx ON model_template_versions(deleted_at) WHERE deleted_at IS NULL;
