CREATE TABLE IF NOT EXISTS relationships (
  source_record_id   UUID NOT NULL REFERENCES record_identifiers(id),
  target_record_id   UUID NOT NULL REFERENCES record_identifiers(id),

  relationship_type  VARCHAR(64) NOT NULL DEFAULT '',

  created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at         TIMESTAMPTZ,

  CONSTRAINT relationships_key
    UNIQUE (source_record_id, target_record_id, relationship_type)
);

CREATE INDEX relationships_source_record_id_idx
        ON relationships (source_record_id)
        WHERE deleted_at IS NULL;

CREATE INDEX relationships_target_record_id_idx
        ON relationships (target_record_id)
        WHERE deleted_at IS NULL;

CREATE FUNCTION enforce_relationships_records_dataset() RETURNS trigger AS $$
DECLARE
  source_record_dataset_id INT;
  target_record_dataset_id INT;
BEGIN
  EXECUTE format(
    'SELECT r.dataset_id FROM %I.record_identifiers r WHERE r.id = $1',
    TG_TABLE_SCHEMA)
  USING NEW.source_record_id
  INTO  source_record_dataset_id;

  EXECUTE format(
      'SELECT r.dataset_id FROM %I.record_identifiers r WHERE r.id = $1',
      TG_TABLE_SCHEMA)
  USING NEW.target_record_id
  INTO  target_record_dataset_id;

  IF source_record_dataset_id IS DISTINCT FROM target_record_dataset_id THEN
    RAISE EXCEPTION 'related records must belong to the same dataset';
  END IF;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER relationships_source_target_record_id_dataset_id_check
BEFORE INSERT OR UPDATE ON relationships
FOR EACH ROW EXECUTE FUNCTION enforce_relationships_records_dataset();
