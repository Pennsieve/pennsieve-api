CREATE TABLE dataset_status_log (
    id SERIAL PRIMARY KEY,
    dataset_id INT NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
    status_id INT REFERENCES dataset_status(id) ON DELETE SET NULL,
    status_name TEXT NOT NULL,
    status_display_name TEXT NOT NULL,
    user_id INT REFERENCES pennsieve.users(id) ON DELETE SET NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX dataset_status_log_dataset_id_idx ON dataset_status_log(dataset_id);
CREATE INDEX dataset_status_log_user_id_idx ON dataset_status_log(user_id);
CREATE INDEX dataset_status_log_status_id_idx ON dataset_status_log(status_id);