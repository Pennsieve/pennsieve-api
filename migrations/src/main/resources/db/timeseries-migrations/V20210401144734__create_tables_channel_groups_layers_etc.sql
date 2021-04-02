CREATE FUNCTION sort_text_array(text[])  RETURNS text[] AS
        $$
            SELECT array_agg(n ORDER BY n) FROM unnest($1) AS t(n);
        $$ LANGUAGE sql STRICT IMMUTABLE;;

CREATE TABLE channel_groups (
    id SERIAL NOT NULL,
    channels TEXT[] NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_unique_timeseries_column_groups_channels ON channel_groups (sort_text_array(channels));

CREATE INDEX idx_gin_timeseries_column_groups_channels ON channel_groups USING gin (channels);

CREATE TABLE layers (
    id SERIAL NOT NULL,
    time_series_id VARCHAR NOT NULL,
    name VARCHAR NOT NULL,
    description TEXT,
    PRIMARY KEY (id),
    UNIQUE (time_series_id, name)
);

CREATE TABLE annotations (
    id SERIAL NOT NULL,
    time_series_id VARCHAR NOT NULL,
    channel_group_id INTEGER NOT NULL,
    layer_id INTEGER,
    name VARCHAR NOT NULL,
    label VARCHAR NOT NULL,
    description TEXT,
    user_id VARCHAR,
    range INT8RANGE,
    PRIMARY KEY (id),
    FOREIGN KEY(channel_group_id) REFERENCES channel_groups (id),
    FOREIGN KEY(layer_id) REFERENCES layers (id)
);

CREATE INDEX annotations_range_index ON annotations USING gist (range);
