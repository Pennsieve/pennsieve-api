CREATE TABLE dataset_status (
    id SERIAL PRIMARY KEY,
    name TEXT UNIQUE NOT NULL,
    display_name TEXT UNIQUE NOT NULL,
    original_name TEXT,
    color TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT dataset_status_name_length_check
    CHECK (char_length(name) > 0 AND char_length(name) <= 28),

    CONSTRAINT dataset_status_display_name_length_check
    CHECK (char_length(display_name) > 0 AND char_length(display_name) <= 28)
);

CREATE TRIGGER dataset_status_update_updated_at
BEFORE UPDATE ON dataset_status
FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

-- Populate default dataset status options.

INSERT INTO dataset_status (name, display_name, original_name, color)
VALUES ('NO_STATUS', 'No Status','NO_STATUS', '#71747C'),
       ('WORK_IN_PROGRESS', 'Work in Progress', 'WORK_IN_PROGRESS', '#2760FF'),
       ('IN_REVIEW', 'In Review', 'IN_REVIEW', '#FFB000'),
       ('COMPLETED', 'Completed', 'COMPLETED', '#17BB62');

-- Join existing dataset status choices to the new defaults.

ALTER TABLE datasets
    ADD COLUMN status_id INT REFERENCES dataset_status(id) ON DELETE RESTRICT;

UPDATE datasets
SET status_id = subquery.status_id
FROM (
    SELECT datasets.id AS dataset_id, dataset_status.id AS status_id
    FROM datasets
    LEFT JOIN dataset_status ON datasets.status = dataset_status.name
) AS subquery
WHERE datasets.id = subquery.dataset_id;

ALTER TABLE datasets
    ALTER COLUMN status_id SET NOT NULL;
