UPDATE dataset_publication_log
SET publication_type = 'removal'
WHERE publication_type = 'withdrawal';
