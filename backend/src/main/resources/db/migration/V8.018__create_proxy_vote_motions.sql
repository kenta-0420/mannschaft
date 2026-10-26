-- F08.3: 議決権行使・委任状 — 議案テーブル
CREATE TABLE proxy_vote_motions (
    id                  BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    session_id          BIGINT UNSIGNED  NOT NULL COMMENT 'FK → proxy_vote_sessions',
    motion_number       INT UNSIGNED     NOT NULL COMMENT '議案番号（表示順。1始まり）',
    title               VARCHAR(200)     NOT NULL COMMENT '議案タイトル',
    description         TEXT             NULL     COMMENT '議案の詳細説明',
    voting_status       VARCHAR(20)      NOT NULL DEFAULT 'PENDING' COMMENT '議案別投票状態（PENDING / VOTING / VOTED）',
    vote_deadline_at    DATETIME         NULL     COMMENT '投票自動終了日時',
    required_approval   VARCHAR(20)      NOT NULL DEFAULT 'MAJORITY' COMMENT '可決要件（MAJORITY / TWO_THIRDS / UNANIMOUS）',
    result              VARCHAR(20)      NULL     COMMENT '採決結果（APPROVED / REJECTED）',
    approve_count       INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '賛成票数',
    reject_count        INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '反対票数',
    abstain_count       INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '棄権票数',
    created_at          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_pvm_session (session_id, motion_number),

    CONSTRAINT fk_pvm_session FOREIGN KEY (session_id) REFERENCES proxy_vote_sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.3 議案';
