ALTER TABLE datasets
    ADD COLUMN banner_id UUID REFERENCES dataset_assets(id),
    ADD COLUMN readme_id UUID REFERENCES dataset_assets(id);
