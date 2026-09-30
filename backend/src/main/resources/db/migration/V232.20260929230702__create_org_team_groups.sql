-- 組織に加盟するチームを区分するチームグループ（F01.2.1 §5.2）。平坦・並び順付き・論理削除
CREATE TABLE org_team_groups (
    id              BINARY(16)       NOT NULL COMMENT 'UUIDv7',
    organization_id BIGINT UNSIGNED  NOT NULL COMMENT '組織ID（クロスドメインFKなし）',
    name            VARCHAR(50)      NOT NULL COMMENT '表示名',
    description     VARCHAR(200)     NULL     COMMENT '補足説明',
    sort_order      INT              NOT NULL COMMENT '並び順（昇順）',
    created_by      BIGINT UNSIGNED  NULL,
    updated_by      BIGINT UNSIGNED  NULL,
    created_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME         NULL,
    active_name     VARCHAR(50)      GENERATED ALWAYS AS (IF(deleted_at IS NULL, name, NULL)) STORED COMMENT '生存行だけの名前（一意制約用の生成列）',
    PRIMARY KEY (id),
    UNIQUE KEY uq_org_team_groups_org_active_name (organization_id, active_name),
    INDEX idx_org_team_groups_org_sort (organization_id, deleted_at, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='チームグループ（組織内のチーム区分）';
