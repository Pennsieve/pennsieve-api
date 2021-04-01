ALTER TABLE tokens
ADD CONSTRAINT unique_cognito_id UNIQUE (cognito_id);
