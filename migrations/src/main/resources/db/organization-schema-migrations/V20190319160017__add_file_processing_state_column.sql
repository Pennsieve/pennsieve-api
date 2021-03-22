ALTER TABLE files
  ADD COLUMN processing_state VARCHAR(255);

UPDATE files
  SET processing_state = CASE
    WHEN object_type = 'source' THEN 'processed'
    ELSE 'not_processable'
    END;

ALTER TABLE files
  ALTER COLUMN processing_state
  SET NOT NULL;

ALTER TABLE files
  ADD CONSTRAINT processing_state_constraint
  CHECK (
    (object_type = 'source' AND processing_state = 'processed') OR
    (object_type = 'source' AND processing_state = 'unprocessed') OR
    (object_type = 'file' AND processing_state = 'not_processable') OR
    (object_type = 'view' AND processing_state = 'not_processable')
  );
