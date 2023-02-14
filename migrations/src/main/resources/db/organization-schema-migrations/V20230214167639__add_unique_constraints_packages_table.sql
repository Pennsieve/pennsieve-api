CREATE UNIQUE INDEX packages_name_dataset_id__parent_id_null ON packages ("name", "dataset_id") WHERE parent_id IS NULL;

CREATE UNIQUE INDEX packages_name_dataset_id ON packages ("name", "parent_id")