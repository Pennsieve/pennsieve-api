CREATE TABLE changelog_events(
    id SERIAL PRIMARY KEY,
    dataset_id INTEGER NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
    -- No FK reference because service user (id=0) can create events
    user_id INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    detail JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
