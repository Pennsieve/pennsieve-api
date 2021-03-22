CREATE TABLE dataset_ignore_files (
  id SERIAL PRIMARY KEY,
  dataset_id INT NOT NULL,
  file_name TEXT NOT NULL
);
