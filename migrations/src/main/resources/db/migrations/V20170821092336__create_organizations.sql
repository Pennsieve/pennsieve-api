CREATE TABLE organizations(
  id SERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  slug VARCHAR(255),
  encryption_key_id VARCHAR(255) NOT NULL,
  terms VARCHAR(255),
  status VARCHAR(255),
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  node_id VARCHAR(255)
);

CREATE INDEX organiations_tmp_node_id_idx ON organizations(node_id);

CREATE TRIGGER organizations_update_updated_at BEFORE UPDATE ON organizations FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
