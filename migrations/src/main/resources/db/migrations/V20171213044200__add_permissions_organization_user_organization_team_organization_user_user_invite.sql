ALTER TABLE organization_user ADD COLUMN permission_bit INTEGER DEFAULT 0;
ALTER TABLE organization_user ADD COLUMN created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE organization_user ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

CREATE TRIGGER organization_user_update_updated_at BEFORE UPDATE ON organization_user FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

ALTER TABLE organization_team ADD COLUMN permission_bit INTEGER DEFAULT 0;
ALTER TABLE organization_team ADD COLUMN created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE organization_team ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

CREATE TRIGGER organization_team_update_updated_at BEFORE UPDATE ON organization_team FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();

ALTER TABLE team_user ADD COLUMN permission_bit INTEGER DEFAULT 0;
ALTER TABLE team_user ADD COLUMN created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE team_user ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE user_invite ADD COLUMN permission_bit INTEGER DEFAULT 0;

UPDATE pennsieve.user_invite
    SET permission_bit =
        CASE
            WHEN permission = 'collaborate' THEN 1
            WHEN permission = 'read' THEN 2
            WHEN permission = 'write' THEN 4
            WHEN permission = 'delete' THEN 8
            WHEN permission = 'administer' THEN 16
            WHEN permission = 'owner' THEN 32
            ELSE 0
        END;