ALTER TABLE webhooks
  DROP CONSTRAINT webhooks_created_by_fkey;

ALTER TABLE webhooks
  ADD CONSTRAINT webhooks_created_by_fkey
  FOREIGN KEY (created_by)
  REFERENCES pennsieve.users(id)
  ON DELETE RESTRICT;

ALTER TABLE webhooks
  ALTER COLUMN created_by
  SET NOT NULL;
