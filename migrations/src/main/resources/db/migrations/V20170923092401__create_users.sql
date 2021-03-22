CREATE TABLE users(
  id SERIAL PRIMARY KEY,
  email VARCHAR(255) NOT NULL,
  password VARCHAR(255) NOT NULL,
  first_name VARCHAR(255),
  last_name VARCHAR(255),
  credential VARCHAR(255),
  color VARCHAR(255),
  url VARCHAR(255),
  authy_id INTEGER,
  accepted_terms VARCHAR(255),
  is_super_admin BOOLEAN,
  preferred_org_id INTEGER,
  status BOOLEAN,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  node_id VARCHAR(255)
);

CREATE INDEX users_tmp_node_id_idx ON users(node_id);

CREATE TRIGGER users_update_updated_at BEFORE UPDATE ON users FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
