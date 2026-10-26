-- F17.1 Phase 1: 村メンバーシップテーブル
-- subject_id (USER/TEAM/ORG) は FK 張らない（原則1）
-- village_id のみ CASCADE（同一ドメイン・原則2）

CREATE TABLE village_memberships (
    id                       BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    village_id               BINARY(16)      NOT NULL                                COMMENT 'FK → villages.id（同一ドメイン）',
    subject_type             VARCHAR(20)     NOT NULL                                COMMENT '参加主体 (USER/TEAM/ORGANIZATION)',
    subject_id               BIGINT UNSIGNED NOT NULL                                COMMENT '参加主体ID（FK 張らない / 原則1）',
    role                     VARCHAR(20)     NOT NULL DEFAULT 'VILLAGER'             COMMENT '村内ロール (HEADMAN/ELDER/VILLAGER/VISITOR)',
    joined_at                DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    left_at                  DATETIME(6)     NULL                                    COMMENT '退村日（論理削除）',
    banned_at                DATETIME(6)     NULL                                    COMMENT 'BAN日（モデレーション）',
    banned_reason            VARCHAR(500)    NULL,
    invited_by_membership_id BINARY(16)      NULL                                    COMMENT '招待元メンバーシップ（オプション・同一ドメイン）',
    created_at               DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                  BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- left_at を含めることで「現役 1 件 + 過去履歴は NULL 値別扱いで複数並存」を両立
    UNIQUE KEY uk_vm_village_subject (village_id, subject_type, subject_id, left_at),
    KEY idx_vm_village_role (village_id, role),
    KEY idx_vm_subject (subject_type, subject_id),
    KEY idx_vm_joined_at (joined_at),
    CONSTRAINT fk_vm_village FOREIGN KEY (village_id) REFERENCES villages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='村メンバーシップ（F17.1）';
