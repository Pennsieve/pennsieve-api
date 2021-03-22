CREATE TABLE channels(
  id SERIAL PRIMARY KEY,
  name VARCHAR(255),
  "start" BIGINT NOT NULL,
  "end" BIGINT NOT NULL,
  unit VARCHAR(255) NOT NULL,
  rate DOUBLE PRECISION NOT NULL,
  "type" VARCHAR(255) NOT NULL,
  "group" VARCHAR(255),
  last_annotation BIGINT NOT NULL,
  spike_duration BIGINT,
  package_id INTEGER NOT NULL REFERENCES packages(id) ON DELETE CASCADE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  properties JSONB default '[]',
  node_id VARCHAR(255)
);

CREATE TRIGGER channels_update_updated_at BEFORE UPDATE ON channels FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
