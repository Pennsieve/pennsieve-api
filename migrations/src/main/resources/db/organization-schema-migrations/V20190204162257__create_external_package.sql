CREATE TABLE externally_linked_files(
  package_id INTEGER PRIMARY KEY NOT NULL REFERENCES packages(id) ON DELETE CASCADE,
  location TEXT NOT NULL,
  description VARCHAR(255),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER externally_linked_files_update_updated_at BEFORE UPDATE ON externally_linked_files FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
