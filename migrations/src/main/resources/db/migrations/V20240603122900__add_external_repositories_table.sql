CREATE TABLE external_repositories
(
    id SERIAL PRIMARY KEY,
    repository TEXT NOT NULL,
    url TEXT NOT NULL,
    organization_id INTEGER NOT NULL references organizations(id),
    user_id INTEGER NOT NULL references users(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER external_repositories_update_updated_at
    BEFORE UPDATE ON external_repositories
    FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

CREATE TABLE external_repo_publishing
(
    repo_id INTEGER NOT NULL references external_repositories(id) ON DELETE CASCADE,
    dataset_id INTEGER,
    status TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER external_repo_publishing_update_updated_at
    BEFORE UPDATE ON external_repo_publishing
    FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

CREATE TABLE external_repo_appstore
(
    repo_id INTEGER NOT NULL references external_repositories(id) ON DELETE CASCADE,
    application_id UUID,
    status TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER external_repo_appstore_update_updated_at
    BEFORE UPDATE ON external_repo_appstore
    FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
