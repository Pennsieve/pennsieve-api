ALTER TABLE dataset_contributor ADD COLUMN contributor_order INT;

WITH computed_order AS
  (SELECT *,
          ROW_NUMBER() OVER (PARTITION BY dataset_id
                             ORDER BY created_at ASC) AS computed_order
   FROM dataset_contributor)
UPDATE dataset_contributor dc
SET contributor_order = computed_order.computed_order
FROM computed_order
WHERE dc.dataset_id = computed_order.dataset_id
  AND dc.contributor_id = computed_order.contributor_id;

ALTER TABLE dataset_contributor ALTER COLUMN contributor_order SET NOT NULL;