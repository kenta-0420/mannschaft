-- F04.8: 連絡先追加申請テーブル
CREATE TABLE contact_requests (
    id              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    requester_id    BIGINT UNSIGNED  NOT NULL COMMENT '申請した側のユーザーID',
    target_id       BIGINT UNSIGNED  NOT NULL COMMENT '申請された側のユーザーID',
    status          VARCHAR(20)      NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING / ACCEPTED / REJECTED / CANCELLED',
    source_type     VARCHAR(30)      NULL
        COMMENT '申請起点: HANDLE_SEARCH / TEAM_SEARCH / ORG_SEARCH / INVITE_URL / AUTO_TEAM / AUTO_ORG',
    source_id       BIGINT UNSIGNED  NULL
        COMMENT 'チーム・組織・招待トークンのID',
    message         VARCHAR(200)     NULL
        COMMENT '申請時の一言メッセージ（表示時はv-textでエスケープ必須）',
    responded_at    DATETIME         NULL,
    expires_at      DATETIME         NULL
        COMMENT 'NULLは無期限',
    created_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_cr_pair_pending (requester_id, target_id, status),
    INDEX idx_cr_target_status   (target_id, status, created_at DESC),
    INDEX idx_cr_requester       (requester_id, status, created_at DESC),
    INDEX idx_cr_cleanup         (status, updated_at),
    CONSTRAINT fk_cr_requester FOREIGN KEY (requester_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_cr_target    FOREIGN KEY (target_id)    REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_cr_status CHECK (status IN ('PENDING','ACCEPTED','REJECTED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='連絡先追加申請';
