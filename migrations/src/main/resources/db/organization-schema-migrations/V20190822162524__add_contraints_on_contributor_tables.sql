ALTER TABLE contributors ADD CONSTRAINT contributors_user_id_key UNIQUE (user_id);
ALTER TABLE contributors ADD CONSTRAINT contributors_email_key UNIQUE (email);

DROP INDEX dataset_contributor_dataset_id_idx;

CREATE INDEX dataset_contributor_dataset_id_idx ON dataset_contributor(dataset_id);
