CREATE TABLE changelog_event_types(
  id SERIAL PRIMARY KEY,
  name TEXT UNIQUE NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE changelog_events
  ADD COLUMN event_type_id INTEGER REFERENCES changelog_event_types(id),
  ALTER COLUMN event_type DROP NOT NULL;
