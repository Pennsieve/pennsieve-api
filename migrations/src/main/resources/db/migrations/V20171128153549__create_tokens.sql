CREATE TABLE tokens(
  id SERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  token VARCHAR(255) NOT NULL,
  secret VARCHAR(255) NOT NULL,
  organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  last_used TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX tokens_token_idx ON tokens(token);

CREATE INDEX tokens_user_id_idx ON tokens(user_id);
