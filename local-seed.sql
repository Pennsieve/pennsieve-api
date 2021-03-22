---
--- seed for local development
---


--- Create view for all files tables
SELECT pennsieve.refresh_union_view('files');

---
--- seed organizations table
---

INSERT INTO "pennsieve"."organizations" (id, name, slug, encryption_key_id, node_id) VALUES
    (1, 'Pennsieve', 'pennsieve', 'this-key', 'N:organization:320813c5-3ea3-4c3b-aca5-9c6221e8d5f8'),
    (2, 'Test Organization', 'test-organization', 'that-key', 'N:organization:4fb6fec6-9b2e-4885-91ff-7b3cf6579cd0');

---
--- seed subscriptions table
---

INSERT INTO "pennsieve"."subscriptions" (id, organization_id, status) VALUES
    (1, 1, 'ConfirmedSubscription'),
    (2, 2, 'ConfirmedSubscription');

---
--- seed users table
---

INSERT INTO "pennsieve"."users" (id, email, password, first_name, last_name, credential, color, url, authy_id, is_super_admin, preferred_org_id, status, node_id) VALUES
(1, 'test@pennsieve.com', '$pbkdf2-sha512$20000$7uEuHPlGHowzgxW7DS3GKrZaAuQ6EnYz$WlEt3eWz8YMM9xkgBHkndRr6t/wFJwgakI/mjYe5mF8', 'Philip', 'Fry', '', '#5FBFF9', '', 0, false, 2, true, 'N:user:99f02be5-009c-4ecd-9006-f016d48628bf'),
(2, 'test2@pennsieve.com', '$pbkdf2-sha512$20000$ulzFKkKxjbLQDOOWedqWeC8Fr/.cUHVl$/jHfgeTh4iT7PlOhycRbcqfGTjRO3hPdT1Nyg4EpQuU', 'John', 'Zoidberg', '', '#5FBFF9', '', 0, false, 2, true, 'N:user:29cb5354-b471-4a72-adae-6fcb262447d9'),
(3, 'etluser@pennsieve.com', '$pbkdf2-sha512$20000$Aw1nrmonWnmFg/u0Wu1P8z3FtQTlhWu.$zbi477nQZiPGXwyZVhLfOKffCRmIF1uHgZFOqj8SHg4', 'ETL', 'User', '', '#474647', '', 0, true, 2, true, 'N:user:4e8c459b-bffb-49e1-8c6a-de2d8190d84e');

--
--- seed first organization
---

INSERT INTO "1"."datasets" (id, name, state, node_id, status_id) VALUES
    (1, 'Pennsieve Dataset', 'READY', 'N:dataset:c0f0db41-c7cb-4fb5-98b4-e90791f8a975', 1); -- not shared with org

INSERT INTO "1"."dataset_user" (dataset_id, user_id, role) VALUES
    (1, 1, 'owner'); -- Pennsieve Dataset, test@pennsieve.com, owner

---
--- seed second organization two
---

INSERT INTO "2"."datasets" (id, name, state, node_id, role, status_id) VALUES
    (1, 'Test Dataset', 'READY', 'N:dataset:149b65da-6803-4a67-bf20-83076774a5c7', 'editor', 1); -- shared with organization editor role

INSERT INTO "2"."dataset_user" (dataset_id, user_id, role) VALUES
    (1, 1, 'owner'); -- Pennsieve Dataset, test@pennsieve.com, owner

---
--- seed organization user mapping
---

INSERT INTO "pennsieve"."organization_user" (organization_id, user_id, permission_bit) VALUES
    (1, 1, 32), -- Pennsieve, test@pennsieve.com, owner
    (1, 3, 16), -- Pennsieve, test@pennsieve.com, owner
    (2, 1, 8), -- Test Organization, test@pennsieve.com, delete
    (1, 2, 8), -- Pennsieve, test2@pennsieve.com, delete
    (2, 2, 32); -- Test Organization, test2@pennsieve.com, owner

---
--- seed API tokens
---

INSERT INTO "pennsieve"."tokens" (id, name, token, secret, organization_id, user_id) VALUES
(1, 'for pennsieve client unit tests', 'c535152c-0564-4865-8fa0-addebb07e020', '$pbkdf2-sha512$20000$ENHVG67NOt8kbtsoJHt3eB90Ff/sRzKi$yI93E2XBh8IoAiOqWSRq2onjLEfqkrCIWQVj35qTMZI', 1, 3),
(2, 'local', '43db6624-68ac-4e60-8ee6-d248b0db0445', '$pbkdf2-sha512$20000$KnP3rPutIN0w1r8AE5VqGG/lskTocw.V$SsXTKk9e5cT0L/ms7D58CIcfcXZP0jnLRET1FqFEVeE', 1, 1),
(3, 'etl_user', '136a239b-40ed-4d91-a2cf-51cde0636f2d', '$pbkdf2-sha512$20000$.3AJaQ/qErlxtAEGZtCkRK1/yTdP1NCq$TYUkJBY1xSHDtArlAxJLg57IBrdWpaJEquTnk5IZOwA', 1, 3);
