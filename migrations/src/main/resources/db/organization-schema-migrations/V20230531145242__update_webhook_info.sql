ALTER TABLE webhooks ADD COLUMN webhook_targets JSONB default '[]';
ALTER TABLE webhooks ADD COLUMN message_schema JSONB default '[]';
