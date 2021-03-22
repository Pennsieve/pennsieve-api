CREATE TABLE dataset_previewer (
  dataset_id INT NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
  user_id INT NOT NULL REFERENCES pennsieve.users(id) ON DELETE CASCADE,
  embargo_access VARCHAR(10) NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY(dataset_id, user_id)
);

CREATE TRIGGER dataset_previewer_update_updated_at BEFORE UPDATE ON dataset_previewer
FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

