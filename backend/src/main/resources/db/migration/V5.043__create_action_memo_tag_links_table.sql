-- F02.5 行動メモ × タグ 中間テーブル
-- 論理削除なし（メモ/タグの論理削除で十分）。
CREATE TABLE action_memo_tag_links (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    memo_id    BIGINT UNSIGNED NOT NULL COMMENT 'FK → action_memos',
    tag_id     BIGINT UNSIGNED NOT NULL COMMENT 'FK → action_memo_tags',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_amtl_memo
        FOREIGN KEY (memo_id) REFERENCES action_memos (id) ON DELETE CASCADE,
    CONSTRAINT fk_amtl_tag
        FOREIGN KEY (tag_id) REFERENCES action_memo_tags (id) ON DELETE CASCADE,
    UNIQUE KEY uq_amtl_memo_tag (memo_id, tag_id),
    INDEX idx_amtl_tag (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
