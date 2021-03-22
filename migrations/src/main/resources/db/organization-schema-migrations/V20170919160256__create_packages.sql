CREATE TABLE packages(
  id SERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  type VARCHAR(40) NOT NULL,
  state VARCHAR(40) NOT NULL,
  dataset_id INTEGER NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
  parent_id INTEGER REFERENCES packages(id) ON DELETE CASCADE,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  attributes JSONB,
  node_id VARCHAR(255)
);

CREATE INDEX packages_tmp_node_id_idx ON packages(node_id);

CREATE TRIGGER packages_update_updated_at BEFORE UPDATE ON packages FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
