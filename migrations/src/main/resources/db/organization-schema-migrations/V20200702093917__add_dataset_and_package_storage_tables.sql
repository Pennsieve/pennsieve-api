CREATE TABLE dataset_storage(
    dataset_id INTEGER PRIMARY KEY REFERENCES datasets(id) ON DELETE CASCADE,
    size BIGINT
);

CREATE TABLE package_storage(
    package_id INTEGER PRIMARY KEY REFERENCES packages(id) ON DELETE CASCADE,
    size BIGINT
);
