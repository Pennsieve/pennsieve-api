-- Update S3 bucket references for the prod -> prd migration

UPDATE dataset_assets
SET s3_bucket = 'prd-dataset-assets-use1'
WHERE s3_bucket = 'prod-dataset-assets-use1';
