CREATE TABLE IF NOT EXISTS datacanvas_public_package(
    datacanvas_id INTEGER NOT NULL references datacanvases(id) ON DELETE CASCADE,
    datacanvas_folder_id INTEGER NOT NULL references datacanvas_folder(id) ON DELETE CASCADE,
    package_node_id VARCHAR(255) UNIQUE NOT NULL,
    CONSTRAINT DCPP_CanvasFolderPackage_Uniqueness UNIQUE(datacanvas_id, datacanvas_folder_id, package_node_id)
);

CREATE INDEX IF NOT EXISTS datacanvas_public_package_idx
    ON datacanvas_public_package(datacanvas_id, datacanvas_folder_id);

