ALTER TABLE external_publications
  ADD CONSTRAINT external_publications_doi_lowercase_check
  CHECK (doi = LOWER(doi));

DROP TRIGGER external_publications_lowercase_doi ON external_publications;
DROP FUNCTION lowercase_doi_on_insert();
