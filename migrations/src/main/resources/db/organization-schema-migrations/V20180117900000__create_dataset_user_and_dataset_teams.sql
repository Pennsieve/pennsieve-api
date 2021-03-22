ALTER TABLE datasets ADD COLUMN permission_bit INTEGER;

CREATE TABLE dataset_user (
  dataset_id INT NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
  user_id INT NOT NULL REFERENCES pennsieve.users(id) ON DELETE CASCADE,
  permission_bit INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY(dataset_id, user_id)
);

CREATE INDEX dataset_user_dataset_id_idx ON dataset_user(dataset_id);
CREATE INDEX dataset_user_user_id_idx ON dataset_user(user_id);

CREATE TRIGGER dataset_user_update_updated_at BEFORE UPDATE ON dataset_user FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

CREATE TABLE dataset_team (
  dataset_id INT NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
  team_id INT NOT NULL REFERENCES pennsieve.teams(id) ON DELETE CASCADE,
  permission_bit INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY(dataset_id, team_id)
);

CREATE INDEX dataset_teams_dataset_id_idx ON dataset_team(dataset_id);
CREATE INDEX dataset_teams_team_id_idx ON dataset_team(team_id);

CREATE TRIGGER dataset_team_update_updated_at BEFORE UPDATE ON dataset_team FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
