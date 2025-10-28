ALTER TABLE models
DROP CONSTRAINT IF EXISTS models_dataset_id_fkey,
ADD CONSTRAINT models_dataset_id_fkey
    FOREIGN KEY (dataset_id)
    REFERENCES datasets(id)
    ON DELETE CASCADE;

ALTER TABLE model_versions
DROP CONSTRAINT IF EXISTS model_versions_model_id_fkey,
ADD CONSTRAINT model_versions_model_id_fkey
    FOREIGN KEY (model_id)
    REFERENCES models(id)
    ON DELETE CASCADE;

ALTER TABLE records
DROP CONSTRAINT IF EXISTS records_dataset_id_fkey,
ADD CONSTRAINT records_dataset_id_fkey
    FOREIGN KEY (dataset_id)
    REFERENCES datasets(id)
    ON DELETE CASCADE;

ALTER TABLE records
DROP CONSTRAINT IF EXISTS records_model_id_dataset_id_fkey,
ADD CONSTRAINT records_model_id_dataset_id_fkey
    FOREIGN KEY (model_id, dataset_id)
    REFERENCES models(id, dataset_id)
    ON DELETE CASCADE;

ALTER TABLE records
DROP CONSTRAINT IF EXISTS records_model_id_model_version_fkey,
ADD CONSTRAINT records_model_id_model_version_fkey
    FOREIGN KEY (model_id, model_version)
    REFERENCES model_versions(model_id, version)
    ON DELETE CASCADE;

ALTER TABLE record_packages
DROP CONSTRAINT IF EXISTS record_packages_package_id_fkey,
ADD CONSTRAINT record_packages_package_id_fkey
    FOREIGN KEY (package_id)
    REFERENCES packages(id)
    ON DELETE CASCADE;

CREATE OR REPLACE FUNCTION cascade_delete_record_packages() RETURNS trigger AS $$
BEGIN
  DELETE FROM record_packages WHERE record_id = OLD.id;
  RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER cascade_delete_record_packages_trigger
AFTER DELETE ON records
FOR EACH ROW EXECUTE FUNCTION cascade_delete_record_packages();

CREATE OR REPLACE FUNCTION cascade_delete_relationships() RETURNS trigger AS $$
BEGIN
  DELETE FROM relationships
  WHERE source_record_id = OLD.id
     OR target_record_id = OLD.id;
  RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER cascade_delete_relationships_trigger
AFTER DELETE ON records
FOR EACH ROW EXECUTE FUNCTION cascade_delete_relationships();
