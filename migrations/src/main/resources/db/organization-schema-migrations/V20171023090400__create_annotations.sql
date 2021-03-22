CREATE TABLE annotation_layers(
  id SERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  color VARCHAR(255) NOT NULL,
  package_id INTEGER NOT NULL REFERENCES packages(id) ON DELETE CASCADE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE annotations(
  id SERIAL PRIMARY KEY,
  description VARCHAR(255) NOT NULL,
  creator_id INTEGER NOT NULL,
  layer_id INTEGER NOT NULL REFERENCES annotation_layers(id) ON DELETE CASCADE,
  path JSONB default '[]',
  attributes JSONB default '[]',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);


CREATE TRIGGER annotation_update_updated_at BEFORE UPDATE ON annotations FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
CREATE TRIGGER annotation_layers_update_updated_at BEFORE UPDATE ON annotation_layers FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

