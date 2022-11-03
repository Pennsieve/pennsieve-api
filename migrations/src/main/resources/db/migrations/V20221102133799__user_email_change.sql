/*
**  the users_audit table tracks changes to the users table
**  - change_type: is one of 'I', 'U' or 'D' for insert, update, or delete
**  - change_time: is the timestamp of when the change occurred
**  - the remaining columns are copied from the users table row affected
*/
CREATE TABLE IF NOT EXISTS users_audit(
    change_type VARCHAR(255) NOT NULL,
    change_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    id INTEGER,
    email VARCHAR(255),
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    credential VARCHAR(255),
    color VARCHAR(255),
    url VARCHAR(255),
    authy_id INTEGER,
    is_super_admin BOOLEAN,
    preferred_org_id INTEGER,
    status BOOLEAN,
    updated_at TIMESTAMP,
    created_at TIMESTAMP,
    node_id VARCHAR(255),
    orcid_authorization JSONB,
    middle_initial VARCHAR(1),
    degree VARCHAR(255),
    cognito_id UUID,
    is_integration_user BOOLEAN
);

DROP INDEX IF EXISTS users_audit_id_idx;
CREATE INDEX users_audit_id_idx ON users_audit(id);

/*
**  The process_users_audit_tracking() function and users_audit_tracking trigger
**  are the means by which the users_audit rows are created.
*/
CREATE OR REPLACE FUNCTION process_users_audit_tracking() RETURNS trigger AS $process_users_audit_tracking$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        INSERT INTO pennsieve.users_audit SELECT 'D', now(), OLD.*;
    ELSIF (TG_OP = 'UPDATE') THEN
        INSERT INTO pennsieve.users_audit SELECT 'U', now(), NEW.*;
    ELSIF (TG_OP = 'INSERT') THEN
        INSERT INTO pennsieve.users_audit SELECT 'I', now(), NEW.*;
    END IF;
    RETURN NULL; -- result is ignored since this is an AFTER trigger
END
$process_users_audit_tracking$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS users_audit_tracking on users;
CREATE TRIGGER users_audit_tracking
    AFTER INSERT OR UPDATE OR DELETE ON users
    FOR EACH ROW EXECUTE PROCEDURE process_users_audit_tracking();

/*
**  The process_users_updated_email() function and users_updated_email trigger will propagate a
**  user's changed email address to collaborator records in all workspaces that the user is a member.
*/
CREATE OR REPLACE FUNCTION process_users_updated_email() RETURNS trigger AS $process_users_updated_email$
DECLARE
    membership record;
    update_str varchar;
BEGIN
    FOR membership IN SELECT organization_id FROM pennsieve.organization_user WHERE user_id = NEW.id
    LOOP
        update_str = format('update "%s".contributors set email=''%s'' where user_id = %s', membership.organization_id, NEW.email, NEW.id);
        EXECUTE update_str;
    END LOOP;
    RETURN NEW;
END;
$process_users_updated_email$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS users_updated_email ON users;
CREATE TRIGGER users_updated_email
    AFTER UPDATE ON users
    FOR EACH ROW
    WHEN (OLD.email IS DISTINCT FROM NEW.email)
    EXECUTE PROCEDURE process_users_updated_email();
