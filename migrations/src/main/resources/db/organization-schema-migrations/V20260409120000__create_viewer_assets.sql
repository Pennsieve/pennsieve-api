CREATE TABLE viewer_assets (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  dataset_id  INTEGER NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
  name        VARCHAR(255) NOT NULL,
  asset_type  VARCHAR(255) NOT NULL,
  properties  JSONB DEFAULT '{}',
  s3_bucket   VARCHAR(255) NOT NULL,
  status      VARCHAR(50) NOT NULL DEFAULT 'created',
  created_by  INTEGER,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX viewer_assets_dataset_id_idx ON viewer_assets(dataset_id);

CREATE TRIGGER viewer_assets_update_updated_at
  BEFORE UPDATE ON viewer_assets
  FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

CREATE TABLE viewer_asset_packages (
  viewer_asset_id  UUID NOT NULL REFERENCES viewer_assets(id) ON DELETE CASCADE,
  package_id       INTEGER NOT NULL REFERENCES packages(id) ON DELETE CASCADE,
  created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY(viewer_asset_id, package_id)
);

CREATE INDEX viewer_asset_packages_package_id_idx ON viewer_asset_packages(package_id);
CREATE INDEX viewer_asset_packages_viewer_asset_id_idx ON viewer_asset_packages(viewer_asset_id);

CREATE OR REPLACE FUNCTION viewer_asset_cleanup_trigger() RETURNS TRIGGER AS $$
BEGIN
  INSERT INTO pennsieve.viewer_asset_cleanup_queue (org_id, dataset_id, asset_id, s3_bucket)
  VALUES (current_schema::INTEGER, OLD.dataset_id, OLD.id, OLD.s3_bucket);

  RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER viewer_asset_before_delete
  BEFORE DELETE ON viewer_assets
  FOR EACH ROW EXECUTE PROCEDURE viewer_asset_cleanup_trigger();