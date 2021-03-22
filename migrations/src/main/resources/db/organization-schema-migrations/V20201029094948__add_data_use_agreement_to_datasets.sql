ALTER TABLE datasets
    ADD COLUMN data_use_agreement_id INT
    REFERENCES data_use_agreements(id)
    ON DELETE RESTRICT;

ALTER TABLE data_use_agreements
    ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT false;

-- Partial index to ensure there is at most one default data use agreement for
-- the organization.

CREATE UNIQUE INDEX data_use_agreements_is_default_constraint
    ON data_use_agreements(is_default)
    WHERE is_default;
