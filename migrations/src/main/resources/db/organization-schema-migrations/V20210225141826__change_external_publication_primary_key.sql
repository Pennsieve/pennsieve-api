ALTER TABLE external_publications DROP CONSTRAINT external_publications_pkey;
ALTER TABLE external_publications ADD PRIMARY KEY (dataset_id, doi, relationship_type);