ALTER TABLE external_repositories ADD COLUMN auto_process BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE external_repositories ADD COLUMN synchronize JSONB;
