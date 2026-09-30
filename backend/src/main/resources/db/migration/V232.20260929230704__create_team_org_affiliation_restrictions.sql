-- 加盟申請・招待の再送を抑止する制限（拒否後の冷却・ブロック・連打防止）（F01.2.1 §5.4）
CREATE TABLE team_org_affiliation_restrictions (
    id               BINARY(16)       NOT NULL COMMENT 'UUIDv7',
    organization_id  BIGINT UNSIGNED  NOT NULL COMMENT '組織ID（クロスドメインFKなし）',
    team_id          BIGINT UNSIGNED  NOT NULL COMMENT 'チームID（FKは張らない）',
    direction        VARCHAR(20)      NOT NULL COMMENT '止める向き: TEAM_APPLY / ORG_INVITE',
    kind             VARCHAR(20)      NOT NULL COMMENT 'COOLDOWN=期限付き / BLOCK=無期限ブロック',
    reason           VARCHAR(20)      NOT NULL COMMENT 'REJECTED / DECLINED / WITHDRAWN / CANCELLED',
    restricted_until DATETIME         NULL     COMMENT 'COOLDOWN の期限（アプリの壁時計・JST）。BLOCK は NULL',
    created_by       BIGINT UNSIGNED  NULL,
    created_at       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_toar_org_team_dir (organization_id, team_id, direction),
    INDEX idx_toar_team_dir (team_id, direction),
    INDEX idx_toar_kind_until (kind, restricted_until),
    CONSTRAINT chk_toar_direction CHECK (direction IN ('TEAM_APPLY','ORG_INVITE')),
    CONSTRAINT chk_toar_kind CHECK (kind IN ('COOLDOWN','BLOCK')),
    CONSTRAINT chk_toar_reason CHECK (reason IN ('REJECTED','DECLINED','WITHDRAWN','CANCELLED')),
    CONSTRAINT chk_toar_until CHECK ((kind = 'BLOCK' AND restricted_until IS NULL)
                                  OR (kind = 'COOLDOWN' AND restricted_until IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='チーム加盟の申請・招待の再送制限';
