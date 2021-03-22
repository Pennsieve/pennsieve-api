ALTER TABLE user_invite
ADD COLUMN cognito_id UUID NOT NULL;

CREATE TABLE cognito_users (
    cognito_id UUID PRIMARY KEY,
    user_id INT NOT NULL REFERENCES users(id)
);
