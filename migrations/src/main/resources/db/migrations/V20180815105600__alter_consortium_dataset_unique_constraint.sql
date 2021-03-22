ALTER TABLE consortium_datasets
    ADD CONSTRAINT unique_versions UNIQUE(version, organization_id, dataset_id, consortium_id);
