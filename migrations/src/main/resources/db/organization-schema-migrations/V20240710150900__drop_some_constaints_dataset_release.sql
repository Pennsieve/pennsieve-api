/*
** drop constraints: permit 'label', 'marker', and 'release_date' to be NULL
*/
ALTER TABLE dataset_release ALTER COLUMN label DROP NOT NULL;
ALTER TABLE dataset_release ALTER COLUMN marker DROP NOT NULL;
ALTER TABLE dataset_release ALTER COLUMN release_date DROP NOT NULL;
