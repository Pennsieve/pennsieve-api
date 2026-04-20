ALTER TABLE packages
    ADD COLUMN replaces_package_id INTEGER DEFAULT NULL
        REFERENCES packages(id) ON DELETE SET NULL,
    ADD COLUMN replaced_by_package_id INTEGER DEFAULT NULL
        REFERENCES packages(id) ON DELETE SET NULL,
    ADD CONSTRAINT packages_only_files_replace
        CHECK (replaces_package_id IS NULL OR type != 'Collection'),
    ADD CONSTRAINT packages_only_files_replaced_by
        CHECK (replaced_by_package_id IS NULL OR type != 'Collection');

CREATE INDEX idx_packages_replaces_package_id
    ON packages(replaces_package_id)
    WHERE replaces_package_id IS NOT NULL;

CREATE INDEX idx_packages_replaced_by_package_id
    ON packages(replaced_by_package_id)
    WHERE replaced_by_package_id IS NOT NULL;