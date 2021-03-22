CREATE TABLE files(
  id SERIAL PRIMARY KEY,
  package_id INTEGER NOT NULL REFERENCES packages(id) ON DELETE CASCADE,
  name VARCHAR(255),
  file_type VARCHAR(255),
  s3_bucket VARCHAR(255),
  s3_key VARCHAR(255),
  object_type VARCHAR(255) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER files_update_updated_at BEFORE UPDATE ON files FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
