DROP INDEX idx_unique_timeseries_column_groups_channels;

DROP FUNCTION sort_text_array(text[]);

CREATE FUNCTION sort_hash_text_array(text[])  RETURNS text AS
        $$
            SELECT md5(array_to_string(array_agg(n ORDER BY n), ',')) FROM unnest($1) AS t(n);
        $$ LANGUAGE sql STRICT IMMUTABLE;;

CREATE INDEX idx_unique_timeseries_column_groups_channels ON channel_groups(sort_hash_text_array(channels));
 