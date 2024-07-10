ALTER TABLE external_repositories RENAME COLUMN repository TO origin;
ALTER TABLE external_repositories ADD COLUMN status TEXT NOT NULL;
