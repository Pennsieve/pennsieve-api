CREATE TABLE pennsieve_terms_of_service (
        user_id INT PRIMARY KEY REFERENCES users(id),
        accepted_version INT NOT NULL,
        accepted_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        );

CREATE TABLE custom_terms_of_service (
        user_id INT NOT NULL REFERENCES users(id),
        organization_id INT NOT NULL REFERENCES organizations(id),
        accepted_version INT NOT NULL,
        accepted_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

        PRIMARY KEY(user_id, organization_id)
        );
