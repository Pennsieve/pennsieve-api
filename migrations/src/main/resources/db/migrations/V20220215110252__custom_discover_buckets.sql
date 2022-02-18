ALTER TABLE organizations
    ADD COLUMN publish_bucket TEXT
        CHECK (char_length(publish_bucket) >= 3 AND char_length(publish_bucket) <= 63);

ALTER TABLE organizations
    ADD COLUMN embargo_bucket TEXT
        CHECK (char_length(embargo_bucket) >= 3 AND char_length(embargo_bucket) <= 63);
