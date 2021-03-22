CREATE TABLE teams(
  id SERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  node_id VARCHAR(255)
);

CREATE INDEX teams_tmp_node_id_idx ON teams(node_id);

CREATE TRIGGER teams_update_updated_at BEFORE UPDATE ON teams FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
