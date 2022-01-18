ALTER TABLE users
    ADD COLUMN is_integration_user BOOLEAN NOT NULL DEFAULT False;
