ALTER TABLE records
    ADD COLUMN key_hash TEXT;

UPDATE records
SET    key_hash = record_identifiers.key_hash
FROM   record_identifiers
WHERE  record_identifiers.id = records.id;

CREATE UNIQUE INDEX records_model_id_key_hash_key
     ON records (model_id, key_hash)
  WHERE key_hash IS NOT NULL
    AND is_current;

ALTER TABLE records
    DROP CONSTRAINT records_id_model_id_fkey,
    DROP CONSTRAINT records_id_dataset_id_fkey;

ALTER TABLE relationships
    DROP CONSTRAINT relationships_source_record_id_fkey,
    DROP CONSTRAINT relationships_target_record_id_fkey;

DROP TRIGGER  relationships_source_target_record_id_dataset_id_check ON relationships;
DROP FUNCTION enforce_relationships_records_dataset();

CREATE FUNCTION enforce_relationships_records_dataset() RETURNS trigger AS $$
DECLARE
    source_record_dataset_id  INT;
    target_record_dataset_id  INT;
BEGIN
  EXECUTE format(
    'SELECT dataset_id FROM %I.records WHERE id = $1 LIMIT 1',
    TG_TABLE_SCHEMA)
  INTO  source_record_dataset_id
  USING NEW.source_record_id;

  IF source_record_dataset_id IS NULL THEN
      RAISE EXCEPTION 'source_record_id % does not exist', NEW.source_record_id;
  END IF;

  EXECUTE format(
    'SELECT dataset_id FROM %I.records WHERE id = $1 LIMIT 1',
    TG_TABLE_SCHEMA)
  INTO  target_record_dataset_id
  USING NEW.target_record_id;

  IF target_record_dataset_id IS NULL THEN
      RAISE EXCEPTION 'target_record_id % does not exist', NEW.target_record_id;
  END IF;

  IF source_record_dataset_id IS DISTINCT FROM target_record_dataset_id THEN
      RAISE EXCEPTION 'related records must belong to the same dataset';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER relationships_source_target_record_id_dataset_id_check
BEFORE INSERT OR UPDATE ON relationships
FOR EACH ROW EXECUTE FUNCTION enforce_relationships_records_dataset();

ALTER TABLE record_packages
    DROP CONSTRAINT record_packages_record_id_fkey;

DROP TRIGGER  record_packages_dataset_check ON record_packages;
DROP FUNCTION enforce_record_packages_dataset();

CREATE FUNCTION enforce_record_packages_dataset() RETURNS trigger AS $$
DECLARE
    record_dataset_id INT;
    package_dataset_id INT;
BEGIN
  EXECUTE format(
    'SELECT dataset_id FROM %I.records WHERE id = $1 LIMIT 1',
    TG_TABLE_SCHEMA)
  INTO  record_dataset_id
  USING NEW.record_id;

  IF record_dataset_id IS NULL THEN
      RAISE EXCEPTION 'record_id % does not exist', NEW.record_id;
  END IF;

  EXECUTE format(
    'SELECT dataset_id FROM %I.packages WHERE id = $1 LIMIT 1',
    TG_TABLE_SCHEMA)
  INTO  package_dataset_id
  USING NEW.package_id;

  IF package_dataset_id IS NULL THEN
      RAISE EXCEPTION 'package_id % does not exist', NEW.package_id;
  END IF;

  IF record_dataset_id IS DISTINCT FROM package_dataset_id THEN
      RAISE EXCEPTION 'record and package must belong to the same dataset';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER record_packages_dataset_check
BEFORE INSERT OR UPDATE ON record_packages
FOR EACH ROW EXECUTE FUNCTION enforce_record_packages_dataset();

DROP TABLE record_identifiers;
