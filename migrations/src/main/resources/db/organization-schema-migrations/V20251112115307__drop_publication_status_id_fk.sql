-- Drop the foreign key constraint from datasets table to dataset_publication_log
ALTER TABLE datasets DROP CONSTRAINT IF EXISTS datasets_publication_status_id_fkey;

-- Drop the publication_status_id column from datasets table
ALTER TABLE datasets DROP COLUMN IF EXISTS publication_status_id;
