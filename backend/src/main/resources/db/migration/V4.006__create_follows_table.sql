-- F04.4 フォローテーブル
CREATE TABLE follows (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    follower_type VARCHAR(20) NOT NULL,
    follower_id   BIGINT      NOT NULL,
    followed_type VARCHAR(20) NOT NULL,
    followed_id   BIGINT      NOT NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_follows (follower_type, follower_id, followed_type, followed_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
