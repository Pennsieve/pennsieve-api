CREATE TABLE user_invite(
  id SERIAL PRIMARY KEY,
  organization_id INTEGER NOT NULL REFERENCES organizations(id),
  email VARCHAR(255),
  permission VARCHAR(255),
  token VARCHAR(255),
  valid_until TIMESTAMP NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  node_id VARCHAR(255)
);

CREATE INDEX user_invite_organization_id_idx ON user_invite(organization_id);
CREATE INDEX user_invite_email_idx ON user_invite(email);
CREATE INDEX user_invite_token_idx ON user_invite(token);

CREATE TRIGGER user_invite_update_updated_at BEFORE UPDATE ON user_invite FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
