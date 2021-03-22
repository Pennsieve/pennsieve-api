CREATE TABLE datasets_provenance (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id),             --who copied it
    destination_dataset INTEGER NOT NULL,                      --what dataset did they copy it to
    source_dataset INTEGER NOT NULL,                           --where did they copy it from?  (could be a local dataset ID OR a consortium datasetID)

    source_organization INTEGER REFERENCES organizations(id),  --if it came from an organization
    source_consortium INTEGER REFERENCES consortiums(id),      --if it came from a consortium

    destination_organization INTEGER REFERENCES organizations(id), 
    destination_consortium INTEGER REFERENCES consortiums(id),

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

