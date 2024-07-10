/*
** drop constraints: permit 'label', 'marker', and 'release_date' to be NULL
*/
ALTER TABLE external_repositories ALTER COLUMN label DROP NOT NULL;
ALTER TABLE external_repositories ALTER COLUMN marker DROP NOT NULL;
ALTER TABLE external_repositories ALTER COLUMN release_date DROP NOT NULL;
