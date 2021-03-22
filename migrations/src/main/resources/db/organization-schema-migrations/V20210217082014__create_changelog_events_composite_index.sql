-- Used by both the /timeline and /events queries
CREATE INDEX changelog_events_composite_idx
ON changelog_events(dataset_id, event_type_id, created_at desc, id desc, user_id);
