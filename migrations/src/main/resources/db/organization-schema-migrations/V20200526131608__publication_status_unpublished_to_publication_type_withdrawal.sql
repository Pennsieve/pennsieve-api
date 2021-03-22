UPDATE dataset_publication_log
SET publication_status = 'completed', publication_type = 'withdrawal'
WHERE publication_status = 'unpublished';
