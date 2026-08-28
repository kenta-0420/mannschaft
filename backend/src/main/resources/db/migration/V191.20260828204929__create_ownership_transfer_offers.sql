-- F01.2: オーナー委譲の承諾型化（2026-07-18 マスター御裁可）
-- 承諾型オファー（打診→承諾で ADMIN 委譲を実行）を管理する新規テーブル。
--
-- 設計書: docs/features/F01.2_org_team_member_role/01_db_design.md #ownership_transfer_offers
-- - 主キーは UUIDv7（原則6・新規テーブル）→ BINARY(16)
-- - team_id / organization_id の XOR（chk_oto_scope）
-- - issued_by / target_user_id（user ドメイン）・team_id / organization_id（team/org ドメイン）は
--   いずれも本テーブル（role ドメイン）から見て別ドメイン参照のため FK は張らず INDEX のみ（原則1）
-- - status は VARCHAR + アプリ層検証（ENUM にしない）

CREATE TABLE ownership_transfer_offers (
    id               BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK（原則6）',
    team_id          BIGINT UNSIGNED NULL                                    COMMENT '委譲対象がチームの場合（FK 張らない・原則1）',
    organization_id  BIGINT UNSIGNED NULL                                    COMMENT '委譲対象が組織の場合（FK 張らない・原則1）',
    issued_by        BIGINT UNSIGNED NOT NULL                                COMMENT '発行者（現 ADMIN）の user ID（FK 張らない）',
    target_user_id   BIGINT UNSIGNED NOT NULL                                COMMENT '指名相手（承諾できる唯一のユーザー）の user ID（FK 張らない）',
    status           VARCHAR(20)     NOT NULL DEFAULT 'PENDING'              COMMENT 'PENDING/ACCEPTED/DECLINED/EXPIRED/CANCELLED',
    expires_at       DATETIME        NOT NULL                                COMMENT '有効期限（発行から7日を既定）',
    accepted_at      DATETIME        NULL                                    COMMENT '承諾日時（ACCEPTED 時のみ）',
    resolved_at      DATETIME        NULL                                    COMMENT '辞退/取消/期限確定の処理日時',
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_oto_target_user (target_user_id, status)                        COMMENT '自分宛ての PENDING オファー一覧',
    KEY idx_oto_team (team_id, status)                                      COMMENT 'チーム別 PENDING オファー',
    KEY idx_oto_org (organization_id, status)                              COMMENT '組織別 PENDING オファー',
    CONSTRAINT chk_oto_scope CHECK ((team_id IS NULL) <> (organization_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='オーナー委譲 承諾型オファー（F01.2）';
