CREATE TABLE viewer_asset_cleanup_queue (
  id          SERIAL PRIMARY KEY,
  org_id      INTEGER NOT NULL,
  dataset_id  INTEGER NOT NULL,
  asset_id    UUID NOT NULL,
  s3_bucket   VARCHAR(255) NOT NULL,
  s3_prefix   VARCHAR(512) NOT NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);