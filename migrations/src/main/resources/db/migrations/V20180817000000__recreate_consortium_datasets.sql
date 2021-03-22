DROP TABLE consortium_datasets;

CREATE TABLE consortium_datasets(
    id SERIAL PRIMARY KEY,
    consortium_id INT NOT NULL REFERENCES consortiums(id),
    organization_id INT NOT NULL REFERENCES organizations(id),
    source_dataset_id INT NOT NULL,
    published_dataset_id INT NOT NULL,
    user_id INT NOT NULL REFERENCES users(id),
    version INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    total_size INTEGER,
    model_count JSONB,
    file_count JSONB,
    record_count INTEGER,
    description TEXT,
    s3path TEXT,
    s3bucket TEXT,
    status TEXT,
    last_published TIMESTAMP
);

ALTER TABLE consortium_datasets
    ADD CONSTRAINT unique_versions UNIQUE(version, organization_id, source_dataset_id, consortium_id);
