CREATE TABLE feature_flags(
  organization_id INTEGER NOT NULL REFERENCES organizations(id),
  feature VARCHAR(255),
  enabled BOOLEAN,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY(organization_id, feature)
);

CREATE INDEX feature_flags_org_status_idx ON feature_flags(organization_id, enabled);

CREATE TRIGGER feature_flags_update_updated_at BEFORE UPDATE ON feature_flags FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
