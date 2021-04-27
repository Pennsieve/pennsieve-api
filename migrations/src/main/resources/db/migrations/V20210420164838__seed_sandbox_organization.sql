INSERT INTO organizations (name, slug, node_id, encryption_key_id) VALUES (
    '__sandbox__', -- name: String
    '__sandbox__',  -- slug: String
    'N:organization:88c078d6-1827-4e14-867b-801448fe0622', -- node_id: String
    'NO_ENCRYPTION_KEY'  -- encryption_key_id: String
);

INSERT INTO feature_flags (organization_id, feature, enabled)
SELECT (id), 'sandbox_org_feature', true FROM organizations WHERE node_id = 'N:organization:88c078d6-1827-4e14-867b-801448fe0622'
