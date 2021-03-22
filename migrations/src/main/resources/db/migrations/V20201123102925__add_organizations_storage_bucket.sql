ALTER TABLE organizations
  ADD COLUMN storage_bucket TEXT
  CHECK (char_length(storage_bucket) >= 3 AND char_length(storage_bucket) <= 63);
