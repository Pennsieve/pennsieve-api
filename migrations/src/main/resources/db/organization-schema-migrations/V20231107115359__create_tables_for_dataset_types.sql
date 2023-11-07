CREATE TABLE dataset_collections
(
    id SERIAL PRIMARY KEY,
    dataset_id INTEGER NOT NULL references datasets(id) ON DELETE CASCADE,
    dataset_order INTEGER,
    reference_type VARCHAR(255) NOT NULL,
    reference_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER dataset_collections_update_updated_at BEFORE UPDATE ON dataset_collections FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

CREATE TABLE dataset_release
(
    id SERIAL PRIMARY KEY,
    dataset_id INTEGER NOT NULL references datasets(id) ON DELETE CASCADE,
    origin VARCHAR(255) NOT NULL,
    url VARCHAR(255) NOT NULL,
    label VARCHAR(255) NOT NULL,
    marker VARCHAR(255) NOT NULL,
    date TIMESTAMP NOT NULL,
    properties JSONB NOT NULL DEFAULT '[]',
    tags VARCHAR(255) ARRAY NOT NULL DEFAULT '{}',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER dataset_release_update_updated_at BEFORE UPDATE ON dataset_release FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
