ALTER TABLE users ADD COLUMN cognito_id UUID;

UPDATE users
SET cognito_id = (
  SELECT cognito_id FROM cognito_users
  WHERE cognito_users.user_id = users.id
)
WHERE cognito_id IS NULL;
