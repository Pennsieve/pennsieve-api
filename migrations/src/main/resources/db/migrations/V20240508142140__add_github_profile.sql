ALTER TABLE users
    ADD COLUMN github_authorization JSONB;

ALTER TABLE users_audit
    ADD COLUMN github_authorization JSONB;


