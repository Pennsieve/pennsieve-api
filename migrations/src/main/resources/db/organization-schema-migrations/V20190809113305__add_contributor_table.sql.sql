CREATE TABLE contributors(
  id SERIAL PRIMARY KEY,
  first_name VARCHAR(255) NOT NULL,
  last_name VARCHAR(255) NOT NULL,
  email VARCHAR(255) NOT NULL,
  orcid VARCHAR(19),
  user_id INTEGER,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER contributors_update_updated_at BEFORE UPDATE ON contributors FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
