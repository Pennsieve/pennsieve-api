CREATE OR REPLACE FUNCTION lowercase_email_on_insert() RETURNS trigger AS $lowercase_email_on_insert$
    BEGIN
        NEW.email = LOWER(NEW.email);
        RETURN NEW;
    END;
$lowercase_email_on_insert$ LANGUAGE plpgsql;

CREATE TRIGGER lowercase_email_on_insert_trigger BEFORE INSERT OR UPDATE ON contributors
    FOR EACH ROW EXECUTE PROCEDURE lowercase_email_on_insert();

UPDATE contributors SET email=LOWER(email);