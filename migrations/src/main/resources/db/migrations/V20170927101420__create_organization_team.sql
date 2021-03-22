CREATE TABLE organization_team(
  organization_id INT NOT NULL REFERENCES organizations(id),
  team_id INT NOT NULL REFERENCES teams(id) ON DELETE CASCADE,

  PRIMARY KEY(organization_id, team_id)
);

CREATE INDEX organization_team_organization_id_idx ON organization_team(organization_id);
CREATE INDEX organization_team_team_id_idx ON organization_team(team_id);
