CREATE INDEX files_package_id_idx ON files(package_id);
CREATE INDEX channels_package_id_idx ON channels(package_id);
CREATE INDEX columns_package_id_idx ON columns(package_id);

-- Switch column order so dataset id is primary
ALTER TABLE packages DROP CONSTRAINT packages_import_dataset_id_key;
ALTER TABLE packages ADD CONSTRAINT packages_dataset_id_import_id_key UNIQUE (dataset_id, import_id);
