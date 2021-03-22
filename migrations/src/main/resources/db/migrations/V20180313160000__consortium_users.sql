DROP TABLE IF EXISTS consortium_organization;

CREATE TABLE consortium_user(
  consortium_id INT NOT NULL REFERENCES consortiums(id),
  user_id INT NOT NULL REFERENCES users(id),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(consortium_id,user_id)
);

