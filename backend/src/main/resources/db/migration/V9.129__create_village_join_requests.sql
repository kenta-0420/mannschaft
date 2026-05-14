-- F17.1 Phase 1: 村参加申請テーブル（APPROVAL 村のみ）

CREATE TABLE village_join_requests (
    id                       BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    village_id               BINARY(16)      NOT NULL,
    subject_type             VARCHAR(20)     NOT NULL                                COMMENT 'USER/TEAM/ORGANIZATION',
    subject_id               BIGINT UNSIGNED NOT NULL,
    requester_user_id        BIGINT UNSIGNED NOT NULL                                COMMENT '申請を出した操作者',
    message                  VARCHAR(500)    NULL                                    COMMENT '志望動機',
    status                   VARCHAR(20)     NOT NULL DEFAULT 'PENDING'              COMMENT 'PENDING/APPROVED/REJECTED/WITHDRAWN',
    reviewer_membership_id   BINARY(16)      NULL                                    COMMENT '審査した村長/長老のメンバーシップID',
    reviewed_at              DATETIME(6)     NULL,
    review_comment           VARCHAR(500)    NULL,
    created_at               DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- status を含めることで PENDING 二重申請を DB 層で拒否、履歴は保持
    UNIQUE KEY uk_vjr_pending (village_id, subject_type, subject_id, status),
    KEY idx_vjr_village_status (village_id, status),
    KEY idx_vjr_subject (subject_type, subject_id),
    CONSTRAINT fk_vjr_village FOREIGN KEY (village_id) REFERENCES villages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='村参加申請（F17.1）';
