CREATE TABLE IF NOT EXISTS record_packages (
  record_id   UUID NOT NULL REFERENCES record_identifiers(id),
  package_id  INT  NOT NULL REFERENCES packages(id),

  created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at         TIMESTAMPTZ,

  CONSTRAINT record_packages_key
    UNIQUE (record_id, package_id)
);

CREATE INDEX record_packages_record_id_idx
        ON record_packages (record_id)
        WHERE deleted_at IS NULL;

CREATE INDEX record_packages_package_id_idx
        ON record_packages (package_id)
        WHERE deleted_at IS NULL;

CREATE FUNCTION enforce_record_packages_dataset() RETURNS trigger AS $$
DECLARE
  record_dataset_id INT;
  package_dataset_id INT;
BEGIN
  EXECUTE format(
    'SELECT r.dataset_id FROM %I.record_identifiers r WHERE r.id = $1',
    TG_TABLE_SCHEMA)
  USING NEW.record_id
  INTO  record_dataset_id;

  EXECUTE format(
    'SELECT p.dataset_id FROM %I.packages p WHERE p.id = $1',
    TG_TABLE_SCHEMA)
  USING NEW.package_id
  INTO  package_dataset_id;

  IF record_dataset_id IS DISTINCT FROM package_dataset_id THEN
    RAISE EXCEPTION 'record and package must belong to the same dataset';
  END IF;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER record_packages_dataset_check
BEFORE INSERT OR UPDATE ON record_packages
FOR EACH ROW EXECUTE FUNCTION enforce_record_packages_dataset();
