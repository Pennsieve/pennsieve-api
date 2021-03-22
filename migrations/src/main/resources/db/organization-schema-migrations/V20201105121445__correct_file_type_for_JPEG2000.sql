update files set file_type='JPEG2000' where s3_key ilike '%\.jpx' or s3_key ilike '%\.jp2';
