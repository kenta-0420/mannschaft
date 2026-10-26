-- F08.3: 議決権行使・委任状 — 添付ファイルテーブル（ポリモーフィック: SESSION/MOTION）
CREATE TABLE proxy_vote_attachments (
    id                  BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    target_type         VARCHAR(20)      NOT NULL COMMENT '添付先種別（SESSION / MOTION）',
    target_id           BIGINT UNSIGNED  NOT NULL COMMENT 'SESSION → proxy_vote_sessions.id / MOTION → proxy_vote_motions.id',
    file_key            VARCHAR(500)     NOT NULL COMMENT 'S3 オブジェクトキー',
    original_filename   VARCHAR(255)     NOT NULL COMMENT 'アップロード時の元ファイル名',
    file_size           INT              NOT NULL COMMENT 'ファイルサイズ（バイト）',
    mime_type           VARCHAR(100)     NOT NULL COMMENT 'MIMEタイプ',
    attachment_type     VARCHAR(20)      NOT NULL DEFAULT 'DOCUMENT' COMMENT '種別（MINUTES / DOCUMENT / OTHER）',
    sort_order          SMALLINT         NOT NULL DEFAULT 0 COMMENT '表示順',
    uploaded_by         BIGINT UNSIGNED  NOT NULL COMMENT 'FK → users。アップロード者',
    created_at          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_pva_target (target_type, target_id, sort_order),

    CONSTRAINT fk_pva_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.3 添付ファイル（セッション/議案共通）';
