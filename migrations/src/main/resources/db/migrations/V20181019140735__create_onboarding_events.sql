CREATE TABLE onboarding_events(
  user_id INT NOT NULL REFERENCES users(id),
  event VARCHAR(255) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(user_id, event)
);

CREATE INDEX onboarding_events_user_id_idx ON onboarding_events(user_id);
