ALTER TABLE consortium_datasets 
    ADD COLUMN version INTEGER,
    ADD COLUMN description TEXT,
    ADD COLUMN total_size INTEGER,
    ADD COLUMN model_count JSONB,
    ADD COLUMN file_count JSONB,
    ADD COLUMN record_count INTEGER,
    ADD COLUMN s3path TEXT,
    ADD COLUMN s3bucket TEXT,
    ADD COLUMN status TEXT,
    ADD COLUMN last_published TIMESTAMP;
