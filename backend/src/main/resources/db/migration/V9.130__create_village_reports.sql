-- F17.1 Phase 1: 村内通報テーブル

CREATE TABLE village_reports (
    id                       BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    village_id               BINARY(16)      NOT NULL,
    reporter_user_id         BIGINT UNSIGNED NOT NULL                                COMMENT '通報者（FK 張らない）',
    target_type              VARCHAR(20)     NOT NULL                                COMMENT '通報対象種別 (POST/MESSAGE/MEMBERSHIP/VILLAGE)',
    target_ref_id            VARCHAR(64)     NOT NULL                                COMMENT '対象 ID（型は target_type に依存）',
    reason_code              VARCHAR(64)     NOT NULL                                COMMENT '通報理由コード',
    detail                   TEXT            NULL,
    status                   VARCHAR(20)     NOT NULL DEFAULT 'PENDING'              COMMENT 'PENDING/REVIEWING/RESOLVED/DISMISSED',
    handler_membership_id    BINARY(16)      NULL                                    COMMENT '処理担当の村長/長老メンバーシップID',
    handler_action           VARCHAR(64)     NULL                                    COMMENT 'POST_DELETED/USER_BANNED/NONE 等',
    handled_at               DATETIME(6)     NULL,
    created_at               DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_vr_village_status (village_id, status),
    KEY idx_vr_reporter_created (reporter_user_id, created_at)                       COMMENT 'レートリミット用',
    KEY idx_vr_target (target_type, target_ref_id),
    CONSTRAINT fk_vr_village FOREIGN KEY (village_id) REFERENCES villages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='村内通報（F17.1）';
