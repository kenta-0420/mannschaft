ALTER TABLE users ADD COLUMN purged_at DATETIME NULL;
CREATE INDEX idx_users_deleted_purge ON users(deleted_at, purged_at);
