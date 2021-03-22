UPDATE datasets
SET contributors = CASE
  WHEN full_name = ANY(d.contributors)
  THEN d.contributors
  ELSE array_prepend(full_name::varchar, d.contributors::varchar[])
END
FROM (
  SELECT datasets.*, btrim(users.first_name || ' ' || users.last_name) AS full_name
  FROM datasets
  JOIN dataset_user ON (datasets.id = dataset_user.dataset_id)
  JOIN pennsieve.users ON (dataset_user.user_id = pennsieve.users.id)
  WHERE dataset_user.role = 'owner'
) d
WHERE d.id = datasets.id;
