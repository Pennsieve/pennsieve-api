CREATE TABLE tsannotations (
    id SERIAL NOT NULL,
    name TEXT NOT NULL,
    channel TEXT NOT NULL,
    label TEXT NOT NULL,
    description TEXT,
    userid TEXT,
    layer TEXT,
    starttime BIGINT NOT NULL,
    endtime BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (name, channel)
)

CREATE INDEX time_range ON tsannotations (starttime, endtime)