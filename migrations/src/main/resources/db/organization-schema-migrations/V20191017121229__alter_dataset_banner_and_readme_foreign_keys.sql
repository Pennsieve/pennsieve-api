ALTER TABLE datasets
    DROP CONSTRAINT datasets_banner_id_fkey,
    DROP CONSTRAINT datasets_readme_id_fkey ;
ALTER TABLE datasets
    ADD CONSTRAINT datasets_banner_id_fkey
        FOREIGN KEY (banner_id)
        REFERENCES dataset_assets(id)
        ON DELETE SET NULL,
    ADD CONSTRAINT datasets_readme_id_fkey
        FOREIGN KEY (readme_id)
        REFERENCES dataset_assets(id)
        ON DELETE SET NULL ;