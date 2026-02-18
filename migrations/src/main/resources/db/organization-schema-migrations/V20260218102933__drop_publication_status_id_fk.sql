-- Drop the trigger that automatically updates publication_status_id on datasets table
DROP TRIGGER IF EXISTS dataset_publication_status_update_dataset ON dataset_publication_log;

-- Drop the function that was used by the trigger
DROP FUNCTION IF EXISTS add_dataset_publication_status();

-- Drop the foreign key constraint from datasets table to dataset_publication_log
ALTER TABLE datasets DROP CONSTRAINT IF EXISTS datasets_publication_status_id_fkey;

-- Drop the publication_status_id column from datasets table
ALTER TABLE datasets DROP COLUMN IF EXISTS publication_status_id;
