-- ポイっとメモの添付ファイル（画像）管理
CREATE TABLE quick_memo_attachments (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    memo_id           BIGINT        NOT NULL COMMENT 'FK -> quick_memos.id (ON DELETE CASCADE)',
    s3_key            VARCHAR(512)  NOT NULL UNIQUE COMMENT 'quick-memo/{yyyymm}/{uuid}.{ext}',
    original_filename VARCHAR(255)  NULL,
    content_type      VARCHAR(100)  NOT NULL COMMENT 'image/jpeg, image/png, image/webp, image/gif',
    file_size_bytes   INT           NOT NULL,
    width_px          INT           NULL,
    height_px         INT           NULL,
    sort_order        INT           NOT NULL DEFAULT 0,
    created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_quick_memo_attachments_memo FOREIGN KEY (memo_id) REFERENCES quick_memos (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ポイっとメモ添付ファイル';

CREATE INDEX idx_quick_memo_attachments_memo ON quick_memo_attachments (memo_id);
