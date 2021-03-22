ALTER TABLE datasets DROP CONSTRAINT datasets_publication_status_id_fkey;
ALTER TABLE datasets
    ADD CONSTRAINT datasets_publication_status_id_fkey FOREIGN KEY ("publication_status_id")
    REFERENCES dataset_publication_log(id) ON DELETE SET NULL;
