CREATE TABLE team_user(
  team_id INT NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
  user_id INT NOT NULL REFERENCES users(id),

  PRIMARY KEY(team_id, user_id)
);

CREATE INDEX team_user_team_id_idx ON team_user(team_id);
CREATE INDEX team_user_user_id_idx ON team_user(user_id);
