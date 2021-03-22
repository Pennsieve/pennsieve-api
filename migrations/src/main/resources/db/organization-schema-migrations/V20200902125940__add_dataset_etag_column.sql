ALTER TABLE DATASETS ADD COLUMN etag TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- Drop updated_at trigger before populating ETag

DROP TRIGGER datasets_update_updated_at ON datasets;

-- The updated_at timestamp was previously used as the ETag; keep using the old values

UPDATE datasets SET etag = updated_at;

CREATE OR REPLACE FUNCTION update_etag_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.etag = now();
    RETURN NEW;
END
$$ language 'plpgsql';

-- Update the dataset ETag only if user-controlled columns change

CREATE TRIGGER datasets_update_etag
BEFORE UPDATE ON datasets
FOR EACH ROW
WHEN (
  OLD.name <> NEW.name OR
  OLD.description <> NEW.description OR
  OLD.status_id <> NEW.status_id OR
  OLD.automatically_process_packages <> NEW.automatically_process_packages OR
  OLD.license <> NEW.license OR
  OLD.tags <> NEW.tags
)
EXECUTE PROCEDURE update_etag_column();

-- Same with `updated_at`: only update timestamp if a user changes the columns

CREATE TRIGGER datasets_update_updated_at
BEFORE UPDATE ON datasets
FOR EACH ROW
WHEN (
  OLD.name <> NEW.name OR
  OLD.description <> NEW.description OR
  OLD.status_id <> NEW.status_id OR
  OLD.automatically_process_packages <> NEW.automatically_process_packages OR
  OLD.license <> NEW.license OR
  OLD.tags <> NEW.tags
)
EXECUTE PROCEDURE update_updated_at_column();
