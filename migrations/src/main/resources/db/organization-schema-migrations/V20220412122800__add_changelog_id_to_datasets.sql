ALTER TABLE datasets
    ADD COLUMN changelog_id UUID REFERENCES dataset_assets(id);

