ALTER TABLE webhooks ADD COLUMN target_info JSONB default '[]';
ALTER TABLE webhooks ADD COLUMN message_schema JSONB default '[]';
