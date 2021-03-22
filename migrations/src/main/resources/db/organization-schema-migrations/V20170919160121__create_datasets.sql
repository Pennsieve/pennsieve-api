CREATE TABLE datasets(
  id SERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  state VARCHAR(40) NOT NULL,
  description VARCHAR(255),
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  node_id VARCHAR(255)
);

CREATE INDEX datasets_tmp_node_id_idx ON datasets(node_id);

CREATE TRIGGER datasets_update_updated_at BEFORE UPDATE ON datasets FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
