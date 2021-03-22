ALTER TABLE organizations ADD COLUMN custom_terms_of_service_version TIMESTAMP;

ALTER TABLE custom_terms_of_service DROP COLUMN accepted_version;
ALTER TABLE custom_terms_of_service ADD COLUMN accepted_version TIMESTAMP NOT NULL;

ALTER TABLE pennsieve_terms_of_service DROP COLUMN accepted_version;
ALTER TABLE pennsieve_terms_of_service ADD COLUMN accepted_version TIMESTAMP NOT NULL;
