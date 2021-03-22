ALTER TABLE "subscriptions"
	ADD COLUMN accepted_by VARCHAR,
	ADD COLUMN accepted_for_organization VARCHAR,
	ADD COLUMN accepted_by_user INTEGER;

