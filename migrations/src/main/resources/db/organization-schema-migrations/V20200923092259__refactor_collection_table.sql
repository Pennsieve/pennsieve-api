CREATE TABLE collections(
  id SERIAL PRIMARY KEY,
  name TEXT UNIQUE NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP

  CONSTRAINT collection_name_length_check
  CHECK (char_length(name) > 0 AND char_length(name) <= 255)
);

CREATE TRIGGER collections_update_updated_at BEFORE UPDATE ON collections
FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

CREATE TABLE dataset_collection (
  dataset_id INT NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
  collection_id INT NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY(dataset_id, collection_id)
);

CREATE TRIGGER dataset_collection_update_updated_at BEFORE UPDATE ON dataset_collection FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();


