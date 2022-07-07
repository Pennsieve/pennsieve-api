ALTER TABLE datasets
    DROP CONSTRAINT IF EXISTS datasets_changelog_id_fkey;

ALTER TABLE datasets
    ADD CONSTRAINT datasets_changelog_id_fkey
        FOREIGN KEY (changelog_id)
        REFERENCES dataset_assets(id)
        ON DELETE SET NULL;
