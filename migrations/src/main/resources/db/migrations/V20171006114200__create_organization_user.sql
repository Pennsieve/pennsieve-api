CREATE TABLE organization_user(
  organization_id INT NOT NULL REFERENCES organizations(id),
  user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  PRIMARY KEY(organization_id, user_id)
);

CREATE INDEX organization_user_organization_id_idx ON organization_user(organization_id);
CREATE INDEX organization_user_user_id_idx ON organization_user(user_id);
