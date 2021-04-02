CREATE TABLE unit_ranges (
    id SERIAL NOT NULL, 
    count INTEGER NOT NULL, 
    channel VARCHAR NOT NULL, 
    range INT8RANGE, 
    tsindex VARCHAR, 
    tsblob VARCHAR, 
    PRIMARY KEY (id), 
    EXCLUDE USING gist (channel WITH =, range WITH &&)
);

CREATE INDEX unit_channel_range_index ON unit_ranges USING gist (channel, range);
