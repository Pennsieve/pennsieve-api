ALTER TABLE annotations ADD COLUMN data JSONB;

CREATE INDEX idx_gin_timeseries_annotations_data ON annotations USING gin (data);