UPDATE datasets
SET state = 'READY'
WHERE state IN ('IMPORTING', 'EXPORTING');
