ALTER TABLE datasets ADD COLUMN collection INT REFERENCES dataset_collections(id) ON DELETE SET NULL ;
