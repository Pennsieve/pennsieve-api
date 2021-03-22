CREATE TABLE datasets_provenance (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES pennsieve.users(id),             --who copied it
    source_dataset INTEGER NOT NULL,                                     --where did they copy it from?  (could be a local dataset ID OR a consortium datasetID)
    destination_dataset INTEGER NOT NULL REFERENCES datasets(id),        --what dataset did they copy it to
    source_consortium INTEGER REFERENCES pennsieve.consortiums(id),      --if it came from a consortium
    source_organization INTEGER REFERENCES pennsieve.organizations(id),  --if it came from an organization
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

