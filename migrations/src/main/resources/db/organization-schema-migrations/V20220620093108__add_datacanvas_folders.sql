/*
** 1. create datacanvas_folder table, and index to support lookup by data-canvas id
 */
CREATE TABLE IF NOT EXISTS datacanvas_folder(
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    datacanvas_id INTEGER NOT NULL references datacanvases(id) ON DELETE CASCADE,
    parent_id INTEGER NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    node_id VARCHAR(255) UNIQUE NOT NULL,
    CONSTRAINT DCF_ParentId_and_Name_Uniqueness UNIQUE(parent_id, name),
    CONSTRAINT DCF_FK_MT_ParentId FOREIGN KEY (parent_id) REFERENCES datacanvas_folder(id) ON DELETE CASCADE
);

DROP TRIGGER IF EXISTS datacanvas_folder_updated_at ON datacanvas_folder;
CREATE TRIGGER datacanvas_folder_updated_at BEFORE UPDATE ON datacanvas_folder FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

DROP INDEX IF EXISTS datacanvas_folder_datacanvas_id_idx;
CREATE INDEX IF NOT EXISTS datacanvas_folder_datacanvas_id_idx
    ON datacanvas_folder(datacanvas_id);

/*
** 2. drop datacanvas_id column from datacanvas_package, remove index
 */
DROP INDEX IF EXISTS datacanvas_package_datacanvas_id_idx;
ALTER TABLE datacanvas_package DROP COLUMN IF EXISTS datacanvas_id;

/*
** 3. add datacanvas_folder_id column from datacanvas_package, and index to support lookup by folder id
 */
ALTER TABLE datacanvas_package
    ADD COLUMN IF NOT EXISTS datacanvas_folder_id INTEGER NOT NULL references datacanvas_folder(id) ON DELETE CASCADE;

DROP INDEX IF EXISTS datacanvas_package_folder_id_idx;
CREATE INDEX IF NOT EXISTS datacanvas_package_folder_id_idx
    ON datacanvas_package(datacanvas_folder_id);
