ALTER TABLE datacanvas_package
    ADD COLUMN IF NOT EXISTS datacanvas_id INTEGER NOT NULL references datacanvases(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS datacanvas_package_datacanvas_id_idx
    ON datacanvas_package(datacanvas_id);
