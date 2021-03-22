CREATE TABLE dataset_collections(
  id SERIAL PRIMARY KEY,
  name TEXT UNIQUE NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP

  CONSTRAINT collection_name_length_check
  CHECK (char_length(name) > 0 AND char_length(name) <= 255)
);

CREATE TRIGGER dataset_collections_update_updated_at BEFORE UPDATE ON dataset_collections
FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

