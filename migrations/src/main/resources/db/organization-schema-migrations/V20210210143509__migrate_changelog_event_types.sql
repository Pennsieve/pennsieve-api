INSERT INTO changelog_event_types (name)
SELECT DISTINCT event_type
FROM changelog_events
WHERE event_type IS NOT NULL
ON CONFLICT DO NOTHING;

UPDATE changelog_events
SET event_type_id = (
  SELECT id
  FROM changelog_event_types
  WHERE changelog_events.event_type = changelog_event_types.name
)
WHERE event_type_id IS NULL;

ALTER TABLE changelog_events
  ALTER COLUMN event_type_id SET NOT NULL,
  DROP COLUMN event_type;
