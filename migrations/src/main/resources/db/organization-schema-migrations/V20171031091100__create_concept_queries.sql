CREATE TABLE concept_queries(
  id SERIAL PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES pennsieve.users(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  query JSONB,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX concept_queries_user_id_idx ON concept_queries(user_id);
CREATE INDEX concept_queries_id_user_id_idx ON concept_queries(id, user_id);

CREATE TRIGGER concept_queries_update_updated_at BEFORE UPDATE ON concept_queries FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
