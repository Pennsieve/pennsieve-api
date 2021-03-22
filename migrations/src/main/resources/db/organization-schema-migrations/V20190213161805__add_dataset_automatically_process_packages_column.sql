ALTER TABLE datasets
  ADD COLUMN automatically_process_packages BOOLEAN DEFAULT true; -- set all existing datasets to automatically process packages

ALTER TABLE datasets
  ALTER COLUMN automatically_process_packages SET DEFAULT false; -- set all new datasets to not automatically process packages by default
