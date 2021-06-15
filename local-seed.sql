---
--- seed for local development
---


--- Create view for all files tables
SELECT pennsieve.refresh_union_view('files');

---
--- seed organizations table
---

INSERT INTO "pennsieve"."organizations" (id, name, slug, encryption_key_id, node_id) VALUES
    (2, 'Pennsieve', 'pennsieve', 'this-key', 'N:organization:320813c5-3ea3-4c3b-aca5-9c6221e8d5f8'),
    (3, 'Test Organization', 'test-organization', 'that-key', 'N:organization:4fb6fec6-9b2e-4885-91ff-7b3cf6579cd0');

---
--- seed subscriptions table
---

INSERT INTO "pennsieve"."subscriptions" (id, organization_id, status) VALUES
    (1, 2, 'ConfirmedSubscription'),
    (2, 3, 'ConfirmedSubscription');

---
--- seed users table
---

INSERT INTO "pennsieve"."users" (id, email, first_name, last_name, credential, color, url, authy_id, is_super_admin, preferred_org_id, status, node_id) VALUES
(1, 'test@pennsieve.com', 'Philip', 'Fry', '', '#5FBFF9', '', 0, false, 3, true, 'N:user:99f02be5-009c-4ecd-9006-f016d48628bf'),
(2, 'test2@pennsieve.com', 'John', 'Zoidberg', '', '#5FBFF9', '', 0, false, 3, true, 'N:user:29cb5354-b471-4a72-adae-6fcb262447d9'),
(3, 'etluser@pennsieve.com', 'ETL', 'User', '', '#474647', '', 0, true, 3, true, 'N:user:4e8c459b-bffb-49e1-8c6a-de2d8190d84e');

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
    (2, 1, 32), -- Pennsieve, test@pennsieve.com, owner
    (2, 3, 16), -- Pennsieve, test@pennsieve.com, owner
    (3, 1, 8), -- Test Organization, test@pennsieve.com, delete
    (2, 2, 8), -- Pennsieve, test2@pennsieve.com, delete
    (3, 2, 32); -- Test Organization, test2@pennsieve.com, owner
