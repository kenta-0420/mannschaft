-- F04.1 タイムライン投稿編集履歴テーブル
CREATE TABLE timeline_post_edits (
    id               BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT,
    timeline_post_id BIGINT UNSIGNED   NOT NULL,
    content_before   TEXT     NULL,
    edited_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_post_edits_post FOREIGN KEY (timeline_post_id) REFERENCES timeline_posts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
