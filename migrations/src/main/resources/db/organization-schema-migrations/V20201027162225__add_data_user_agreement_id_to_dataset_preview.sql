ALTER TABLE dataset_previewer ADD COLUMN data_use_agreement_id INTEGER;
ALTER TABLE dataset_previewer
    ADD CONSTRAINT dataset_previewer_data_use_agreement_id_fkey
        FOREIGN KEY (data_use_agreement_id)
        REFERENCES data_use_agreements(id)
        ON DELETE RESTRICT;