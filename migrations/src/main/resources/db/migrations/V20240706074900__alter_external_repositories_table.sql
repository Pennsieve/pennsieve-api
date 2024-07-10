/*
** rename 'repository' to 'origin' to reduce confusion
*/
ALTER TABLE external_repositories RENAME COLUMN repository TO origin;

/*
** add 'status' column to track status of the external repo: enabled, disabled, suspended
*/
ALTER TABLE external_repositories ADD COLUMN status TEXT NOT NULL;

/*
** drop constraints: permit 'label', 'marker', and 'release_date' to be NULL
*/
ALTER TABLE external_repositories ALTER COLUMN label DROP NOT NULL;
ALTER TABLE external_repositories ALTER COLUMN marker DROP NOT NULL;
ALTER TABLE external_repositories ALTER COLUMN release_date DROP NOT NULL;
