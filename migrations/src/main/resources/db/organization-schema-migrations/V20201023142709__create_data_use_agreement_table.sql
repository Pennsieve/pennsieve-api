CREATE TABLE data_use_agreements(
  id SERIAL PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  body TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  CONSTRAINT data_use_agreements_name_length_check
  CHECK (char_length(name) > 0 AND char_length(name) < 255)
);
