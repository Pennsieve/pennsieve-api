CREATE TABLE datacanvases (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    permission_bit INTEGER NOT NULL DEFAULT 0,
    role VARCHAR(50),
    status_id INTEGER NOT NULL references dataset_status(id) ON DELETE RESTRICT
);

CREATE INDEX datacanvases_status_id_idx
    ON datacanvases(status_id);
	
CREATE TRIGGER datacanvases_update_updated_at BEFORE UPDATE ON datacanvases FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();


CREATE TABLE datacanvas_package(
    datacanvas_id INTEGER NOT NULL references datacanvases(id) ON DELETE CASCADE,
    organization_id INTEGER NOT NULL references pennsieve.organizations(id) ON DELETE CASCADE,
    package_id INTEGER NOT NULL,
    dataset_id INTEGER NOT NULL,
    PRIMARY KEY (datacanvas_id, organization_id, package_id)
);

CREATE INDEX datacanvas_package_datacanvas_id_idx
    ON datacanvas_package(datacanvas_id);

CREATE INDEX IF NOT EXISTS datacanvas_package_organization_id_idx
    ON datacanvas_package(organization_id);

CREATE TABLE datacanvas_user(
    datacanvas_id INTEGER NOT NULL REFERENCES datacanvases(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES pennsieve.users(id) ON DELETE CASCADE,
    permission_bit INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    role VARCHAR(50),
    PRIMARY KEY (datacanvas_id, user_id)
);

CREATE INDEX datacanvas_user_datacanvas_id_idx
    ON datacanvas_user(datacanvas_id);

CREATE INDEX IF NOT EXISTS datacanvas_user_user_id_idx
    ON datacanvas_user(user_id);
	
CREATE TRIGGER datacanvas_user_update_updated_at BEFORE UPDATE ON datacanvas_user FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

CREATE TABLE datacanvas_team (
    datacanvas_id INTEGER NOT NULL references datacanvases(id) ON DELETE CASCADE,
    team_id INTEGER NOT NULL references pennsieve.teams(id) ON DELETE CASCADE,
    permission_bit INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    role VARCHAR(50),
    PRIMARY KEY (datacanvas_id, team_id)
);

CREATE INDEX datacanvas_team_datacanvas_id_idx
    ON datacanvas_team(datacanvas_id);

CREATE INDEX datacanvas_team_team_id_idx
    ON datacanvas_team(team_id);

CREATE TRIGGER datacanvas_team_update_updated_at BEFORE UPDATE ON datacanvas_team FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

