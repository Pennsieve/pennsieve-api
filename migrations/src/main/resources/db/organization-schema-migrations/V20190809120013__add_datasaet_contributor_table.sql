CREATE TABLE dataset_contributor (
  dataset_id INT NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
  contributor_id INT NOT NULL REFERENCES contributors(id) ON DELETE CASCADE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY(dataset_id, contributor_id)
);

CREATE INDEX dataset_contributor_dataset_id_idx ON dataset_contributor(contributor_id);
CREATE INDEX dataset_contributor_contributor_id_idx ON dataset_contributor(contributor_id);

CREATE TRIGGER dataset_contributor_update_updated_at BEFORE UPDATE ON dataset_contributor FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
