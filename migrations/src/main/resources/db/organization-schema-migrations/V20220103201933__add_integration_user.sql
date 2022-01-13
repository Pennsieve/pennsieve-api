ALTER TABLE webhooks
    ADD COLUMN integration_user_id INTEGER NOT NULL REFERENCES pennsieve.users(id) ON DELETE CASCADE;

ALTER TABLE webhooks
    ADD COLUMN has_access BOOLEAN NOT NULL DEFAULT false;