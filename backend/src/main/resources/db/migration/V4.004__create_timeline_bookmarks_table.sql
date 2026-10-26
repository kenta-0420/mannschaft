-- F04.1 タイムラインブックマークテーブル
CREATE TABLE timeline_bookmarks (
    id               BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT,
    user_id          BIGINT UNSIGNED NOT NULL,
    timeline_post_id BIGINT UNSIGNED   NOT NULL,
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_bookmarks_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_bookmarks_post FOREIGN KEY (timeline_post_id) REFERENCES timeline_posts(id) ON DELETE CASCADE,
    UNIQUE KEY uk_bookmarks (user_id, timeline_post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
