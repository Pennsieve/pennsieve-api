CREATE TABLE organization_storage(
    organization_id INTEGER PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    size BIGINT
);
