-- Data migration to clean up after
-- https://github.com/Blackfynn/blackfynn-api/pull/2659

DROP TRIGGER packages_update_updated_at ON packages;

UPDATE packages
SET state = 'PROCESSING'
WHERE state IN ('SUBMITTED', 'PENDING', 'RUNNABLE', 'STARTING', 'RUNNING');

UPDATE packages
SET state = 'READY'
WHERE state = 'SUCCEEDED';

CREATE TRIGGER packages_update_updated_at
BEFORE UPDATE ON packages
FOR EACH ROW EXECUTE PROCEDURE update_updated_at_column();
