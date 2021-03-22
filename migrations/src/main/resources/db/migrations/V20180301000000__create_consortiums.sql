CREATE TABLE consortiums(
  id SERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER consortium_update_updated_at BEFORE UPDATE ON consortiums FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

CREATE TABLE consortium_organization(
  consortium_id INT NOT NULL REFERENCES consortiums(id),
  organization_id INT NOT NULL REFERENCES organizations(id),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(consortium_id,organization_id)
);

CREATE TABLE consortium_datasets(
  consortium_id INT NOT NULL REFERENCES consortiums(id),
  organization_id INT NOT NULL REFERENCES organizations(id),
  dataset_id INT NOT NULL,
  user_id INT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(consortium_id,organization_id, dataset_id)
);
