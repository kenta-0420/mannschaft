-- F05.1 掲示板: リアクションテーブル
CREATE TABLE bulletin_reactions (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    target_type VARCHAR(10) NOT NULL,
    target_id   BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    emoji       VARCHAR(10) NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_bulletin_reactions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_bulletin_reactions_target_user_emoji (target_type, target_id, user_id, emoji)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
