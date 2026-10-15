-- F18 Phase 4 第一陣: プロバイダー同義語辞書 + スタンプ押印 Permission 新設
-- 設計書: docs/features/F18_point_card_wallet.md §7.6 / §16
--
-- 本マイグレーションは 2 つの責務を持つ:
--   1. point_card_provider_synonyms テーブル新設（口語・略称・旧称の fuzzy match 補強）
--   2. POINT_CARD_STAMP_ISSUE Permission 新設 + ADMIN/DEPUTY_ADMIN 後方互換登録
--
-- 既存スキーマとの整合性に関する注記:
--   - permissions テーブルは BIGINT UNSIGNED の id + (name, display_name, scope) の構造で、
--     description / is_default カラムは存在しない。
--   - permission_groups にも is_default カラムは存在しない（target_role は持つ）。
--   - そのため、後方互換は permission_group_permissions 経由ではなく
--     既存 V9.116__add_manage_succession_unseal_permission.sql / V18.015 と同じパターンで
--     role_permissions に天井登録する方式を採用する:
--       - ADMIN: is_default=1（自動付与）
--       - DEPUTY_ADMIN: is_default=0（天井のみ。理事長が permission_groups 経由で個別付与）
--   これにより、Phase 4 で押印認可を roleService.hasPermission(..., "POINT_CARD_STAMP_ISSUE")
--   へ切り替えても、現状の ADMIN は無変更で押印可能を維持できる（後方互換）。

-- ============================================================================
-- 1. point_card_provider_synonyms テーブル
-- ============================================================================
CREATE TABLE point_card_provider_synonyms (
    id                  CHAR(36)        NOT NULL,
    provider_id         CHAR(36)        NOT NULL,
    synonym_display     VARCHAR(100)    NOT NULL,
    synonym_normalized  VARCHAR(100)    NOT NULL,
    memo                VARCHAR(200)    NULL,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_pcps_normalized (synonym_normalized),
    INDEX idx_pcps_provider (provider_id),
    CONSTRAINT fk_pcps_provider FOREIGN KEY (provider_id)
        REFERENCES point_card_providers (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- 2. POINT_CARD_STAMP_ISSUE Permission を permissions テーブルに追加
-- ============================================================================
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
VALUES ('POINT_CARD_STAMP_ISSUE', 'F18 スタンプカード押印', 'ORGANIZATION', NOW(), NOW());

-- ============================================================================
-- 3. 後方互換: role_permissions に天井登録
-- ============================================================================
-- 3-1. ADMIN: is_default=1 で自動付与（現状の押印権限 ADMIN/DEPUTY_ADMIN を維持）
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN' AND p.name = 'POINT_CARD_STAMP_ISSUE';

-- 3-2. DEPUTY_ADMIN: is_default=0 で天井のみ登録
--      （ADMIN が permission_groups 経由で個別付与可能。初期状態では押印権限を持たないが、
--       既存実装が DEPUTY_ADMIN を許可していたため、運用上は permission_groups で
--       Phase 4 リリース時に明示付与する移行手順を取る想定）
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 0, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'DEPUTY_ADMIN' AND p.name = 'POINT_CARD_STAMP_ISSUE';

-- 3-3. MEMBER / SUPPORTER / GUEST には天井エントリを作成しない（安全側設計）
