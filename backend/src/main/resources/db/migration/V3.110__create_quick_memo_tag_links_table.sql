-- ポイっとメモとタグの中間テーブル（1メモあたり最大10個はアプリ層で制御）
CREATE TABLE quick_memo_tag_links (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    memo_id    BIGINT UNSIGNED NOT NULL COMMENT 'FK -> quick_memos.id (ON DELETE CASCADE)',
    tag_id     BIGINT UNSIGNED NOT NULL COMMENT 'FK -> tags.id (ON DELETE CASCADE)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_quick_memo_tag_links (memo_id, tag_id),
    CONSTRAINT fk_qm_tag_links_memo FOREIGN KEY (memo_id) REFERENCES quick_memos (id) ON DELETE CASCADE,
    CONSTRAINT fk_qm_tag_links_tag  FOREIGN KEY (tag_id)  REFERENCES tags (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ポイっとメモ-タグ中間テーブル';

CREATE INDEX idx_quick_memo_tag_links_tag ON quick_memo_tag_links (tag_id);
