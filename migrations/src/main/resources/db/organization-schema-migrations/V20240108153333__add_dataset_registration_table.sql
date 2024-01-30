CREATE TABLE dataset_registration
(
    dataset_id INTEGER NOT NULL references datasets(id) ON DELETE CASCADE,
    registry VARCHAR(255) NOT NULL,
    registry_id VARCHAR(255),
    category VARCHAR(255),
    value VARCHAR(255) NOT NULL,
    url VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER dataset_registration_update_updated_at
    BEFORE UPDATE ON dataset_registration
    FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
