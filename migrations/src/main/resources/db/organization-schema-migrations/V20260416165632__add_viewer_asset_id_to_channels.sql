ALTER TABLE channels ADD COLUMN viewer_asset_id uuid NULL;
CREATE INDEX channels_viewer_asset_id_idx ON channels(viewer_asset_id);