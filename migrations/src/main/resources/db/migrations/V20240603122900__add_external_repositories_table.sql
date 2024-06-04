CREATE TABLE external_repositories
(
    id SERIAL PRIMARY KEY,
    repository TEXT NOT NULL,
    url TEXT NOT NULL,
    type TEXT NOT NULL,
    organization_id INTEGER NOT NULL references organizations(id),
    user_id INTEGER NOT NULL references users(id),
    dataset_id INTEGER,
    application_id UUID,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER external_repositories_update_updated_at
    BEFORE UPDATE ON external_repositories
    FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
