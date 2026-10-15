-- F04.1 タイムラインリアクションテーブル
CREATE TABLE timeline_post_reactions (
    id               BIGINT UNSIGNED      NOT NULL AUTO_INCREMENT,
    timeline_post_id BIGINT UNSIGNED      NOT NULL,
    user_id          BIGINT UNSIGNED NOT NULL,
    emoji            VARCHAR(10) NOT NULL,
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_post_reactions_post FOREIGN KEY (timeline_post_id) REFERENCES timeline_posts(id) ON DELETE CASCADE,
    CONSTRAINT fk_post_reactions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_post_reactions (timeline_post_id, user_id, emoji)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
