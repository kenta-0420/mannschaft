-- F08.3: 議決権行使・委任状 — 議案コメントテーブル
CREATE TABLE proxy_vote_motion_comments (
    id          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    motion_id   BIGINT UNSIGNED  NOT NULL COMMENT 'FK → proxy_vote_motions',
    user_id     BIGINT UNSIGNED  NOT NULL COMMENT 'FK → users',
    body        TEXT             NOT NULL COMMENT 'コメント本文（最大1,000文字）',
    created_at  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at  DATETIME         NULL     COMMENT '論理削除日時',

    PRIMARY KEY (id),
    INDEX idx_pvmc_motion (motion_id, created_at),

    CONSTRAINT fk_pvmc_motion FOREIGN KEY (motion_id) REFERENCES proxy_vote_motions(id) ON DELETE CASCADE,
    CONSTRAINT fk_pvmc_user   FOREIGN KEY (user_id)   REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.3 議案コメント・質疑';
