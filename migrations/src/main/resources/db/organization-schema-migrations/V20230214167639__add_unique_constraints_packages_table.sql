CREATE UNIQUE INDEX packages_name_dataset_id__parent_id_null ON "19".packages ("name", "dataset_id") WHERE parent_id IS NULL;

CREATE UNIQUE INDEX packages_name_dataset_id ON "19".packages ("name", "parent_id")