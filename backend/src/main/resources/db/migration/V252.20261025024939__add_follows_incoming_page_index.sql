-- FR-15: 受信チーム・TEAM型をキーに絞り、日時/ID順のページとCOUNTを被覆する。
CREATE INDEX idx_follows_incoming_page
    ON follows (followed_type, followed_id, follower_type, created_at DESC, id DESC, follower_id);
