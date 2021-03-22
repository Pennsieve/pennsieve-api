INSERT INTO team_user(team_id, user_id, permission_bit)
SELECT ot.team_id, ou.user_id, 16
FROM organization_team ot
JOIN organization_user ou ON ot.organization_id = ou.organization_id
LEFT JOIN team_user tu ON ot.team_id = tu.team_id
WHERE ot.system_team_type = 'publishers'
AND ou.is_publisher = TRUE
AND tu.user_id IS NULL;
