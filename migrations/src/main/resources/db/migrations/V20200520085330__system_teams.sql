ALTER TABLE organization_team ADD COLUMN system_team_type VARCHAR(255) DEFAULT NULL;
ALTER TABLE organization_team ADD CONSTRAINT unique_system_team_type UNIQUE(organization_id, system_team_type);

-- temp column to store the org id
ALTER TABLE teams ADD COLUMN organization_id INTEGER;

INSERT INTO teams(name, organization_id, node_id)
SELECT 'Publishers' as name, organizations.id as organization_id, 'N:team:' || gen_random_uuid() as node_id FROM organizations;

INSERT INTO organization_team(organization_id, team_id, permission_bit, system_team_type)
-- note the string used here must be kept in line with the ADT SystemTeamType.scala
SELECT organization_id, id as team_id, 16 as permission_bit, 'publishers' as system_team_type
FROM teams
WHERE organization_id IS NOT NULL;

-- remove the temp column
ALTER TABLE teams DROP COLUMN organization_id;
