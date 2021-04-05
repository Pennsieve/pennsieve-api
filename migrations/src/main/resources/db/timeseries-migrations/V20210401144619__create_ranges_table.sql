CREATE TABLE ranges (
    id SERIAL NOT NULL,
    channel VARCHAR NOT NULL,
    rate FLOAT NOT NULL,
    range INT8RANGE,
    location VARCHAR,
    follows_gap BOOLEAN NOT NULL,
    PRIMARY KEY (id),
    EXCLUDE USING gist (channel WITH =, range WITH &&)
)

CREATE INDEX channel_range_index ON ranges USING gist (channel, range)