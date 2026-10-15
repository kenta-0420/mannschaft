-- F17.1 Phase 1: 村作成申請テーブル
-- 独立テーブル（FK なし）

CREATE TABLE village_creation_requests (
    id                       BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    requester_user_id        BIGINT UNSIGNED NOT NULL                                COMMENT '申請者（FK 張らない）',
    proposed_name            VARCHAR(100)    NOT NULL,
    proposed_slug            VARCHAR(64)     NOT NULL,
    proposed_category        VARCHAR(64)     NULL,
    purpose                  TEXT            NOT NULL                                COMMENT '申請理由',
    proposed_guideline_md    MEDIUMTEXT      NULL,
    status                   VARCHAR(20)     NOT NULL DEFAULT 'PENDING'              COMMENT 'PENDING/APPROVED/REJECTED/WITHDRAWN',
    reviewer_user_id         BIGINT UNSIGNED NULL                                    COMMENT '審査担当（運営）',
    review_comment           TEXT            NULL,
    reviewed_at              DATETIME(6)     NULL,
    created_village_id       BINARY(16)      NULL                                    COMMENT '承認時に作成された村ID',
    created_at               DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_vcr_requester (requester_user_id, status),
    KEY idx_vcr_status_created (status, created_at),
    KEY idx_vcr_created_village (created_village_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='村作成申請（F17.1）';
