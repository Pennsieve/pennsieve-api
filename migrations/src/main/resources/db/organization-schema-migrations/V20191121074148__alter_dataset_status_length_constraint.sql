ALTER TABLE dataset_status
    DROP CONSTRAINT dataset_status_name_length_check,
    DROP CONSTRAINT dataset_status_display_name_length_check;

ALTER TABLE dataset_status
    ADD CONSTRAINT dataset_status_name_length_check
    CHECK (char_length(name) > 0 AND char_length(name) <= 60),

    ADD CONSTRAINT dataset_status_display_name_length_check
    CHECK (char_length(display_name) > 0 AND char_length(display_name) <= 60);
