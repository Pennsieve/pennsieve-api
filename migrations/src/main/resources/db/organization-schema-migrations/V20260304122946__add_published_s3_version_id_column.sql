ALTER TABLE files
    ADD COLUMN published_s3_version_id TEXT DEFAULT NULL
        CONSTRAINT files_published_s3_version_id_non_empty CHECK (published_s3_version_id IS NULL OR published_s3_version_id != '');
