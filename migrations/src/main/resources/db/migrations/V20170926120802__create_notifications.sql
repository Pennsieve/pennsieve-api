CREATE TABLE notifications(
  id SERIAL PRIMARY KEY,
  message_type VARCHAR(255) NOT NULL,
  user_id INTEGER NOT NULL,
  delivery_method VARCHAR(255) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX notifications_user_node_id_idx ON notifications(user_id);
