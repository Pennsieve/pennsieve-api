ALTER TABLE dataset_ignore_files
ADD CONSTRAINT dataset_ignore_files_dataset_id_fkey
FOREIGN KEY (dataset_id)
REFERENCES datasets(id)
ON DELETE CASCADE;
