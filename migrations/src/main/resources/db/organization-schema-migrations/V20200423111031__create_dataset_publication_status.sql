CREATE TABLE dataset_publication_log (
    id SERIAL PRIMARY KEY,
    dataset_id INT NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
    publication_status VARCHAR(50) NOT NULL,
    publication_type VARCHAR(50) NOT NULL,
    comments TEXT,
    created_by INT REFERENCES pennsieve.users(id) ON DELETE SET NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX dataset_publication_log_dataset_id_idx ON dataset_publication_log(dataset_id);

ALTER TABLE datasets
    ADD COLUMN publication_status_id INT REFERENCES dataset_publication_log(id) ON DELETE CASCADE;

CREATE OR REPLACE FUNCTION add_dataset_publication_status() RETURNS trigger AS $body$
    BEGIN
        EXECUTE
        'UPDATE "' || TG_TABLE_SCHEMA || '".datasets SET publication_status_id = $1 WHERE id = $2'
        USING NEW.id, NEW.dataset_id;

        RETURN NEW;
    END;
$body$  language 'plpgsql';

CREATE TRIGGER dataset_publication_status_update_dataset
    AFTER INSERT
    ON dataset_publication_log
    FOR EACH ROW
    EXECUTE PROCEDURE add_dataset_publication_status();