CREATE TABLE webhooks(
  id SERIAL PRIMARY KEY,
  api_url VARCHAR(255) NOT NULL,
  image_url VARCHAR(255),
  description VARCHAR(200) NOT NULL,
  secret VARCHAR(255) NOT NULL,
  name VARCHAR(255) NOT NULL,
  display_name VARCHAR(255) NOT NULL,
  is_private BOOLEAN NOT NULL,
  is_default BOOLEAN NOT NULL,
  is_disabled BOOLEAN NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by INTEGER REFERENCES pennsieve.users(id) ON DELETE SET NULL
  -- organization_id INTEGER NOT NULL REFERENCES pennsieve.organizations(id) ON DELETE CASCADE
);

CREATE TABLE dataset_integrations(
  id SERIAL PRIMARY KEY,
  webhook_id INTEGER NOT NULL REFERENCES webhooks(id) ON DELETE CASCADE,
  dataset_id INTEGER NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
  enabled_by INTEGER NOT NULL REFERENCES pennsieve.users(id) ON DELETE CASCADE,
  enabled_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE webhook_statistics(
  webhook_id INTEGER PRIMARY KEY NOT NULL REFERENCES webhooks(id) ON DELETE CASCADE,
  successes INTEGER NOT NULL DEFAULT 0,
  failures INTEGER NOT NULL DEFAULT 0,
  date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE webhook_event_types(
  id SERIAL PRIMARY KEY,
  event_name VARCHAR(255) NOT NULL
);

CREATE TABLE webhook_event_subscriptions(
  id SERIAL PRIMARY KEY,
  webhook_id INTEGER NOT NULL REFERENCES webhooks(id) ON DELETE CASCADE,
  webhook_event_type_id INTEGER NOT NULL REFERENCES webhook_event_types(id) ON DELETE CASCADE
);
