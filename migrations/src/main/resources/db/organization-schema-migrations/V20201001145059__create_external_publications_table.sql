CREATE TABLE external_publications(
  dataset_id INTEGER NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
  doi TEXT NOT NULL,
  custom_citation TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY (dataset_id, doi),

  CONSTRAINT external_publications_doi_length_check
  CHECK (char_length(doi) > 0)
);

CREATE TRIGGER external_publications_update_updated_at
BEFORE UPDATE ON external_publications
FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

CREATE OR REPLACE FUNCTION lowercase_doi_on_insert()
RETURNS trigger AS $$
  BEGIN
    NEW.doi = LOWER(NEW.doi);
    RETURN NEW;
  END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER external_publications_lowercase_doi
BEFORE INSERT OR UPDATE ON external_publications
FOR EACH ROW EXECUTE PROCEDURE lowercase_doi_on_insert();
