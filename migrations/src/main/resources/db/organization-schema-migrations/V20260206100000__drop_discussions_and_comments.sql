-- Drop triggers first
DROP TRIGGER IF EXISTS comment_update_updated_at ON comments;
DROP TRIGGER IF EXISTS discussion_update_updated_at ON discussions;

-- Drop tables (comments first due to foreign key constraint)
DROP TABLE IF EXISTS comments CASCADE;
DROP TABLE IF EXISTS discussions CASCADE;