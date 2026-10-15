-- F18 Phase 5 第一陣: 残高型操作 Permission 2 種新設（案 C）
-- 設計書: docs/features/F18_point_card_wallet.md §16
--
-- 本マイグレーションは以下の責務を持つ:
--   1. POINT_CARD_BALANCE_OPERATE Permission 新設（CHARGE / SPENT 用）
--   2. POINT_CARD_BALANCE_REFUND  Permission 新設（REFUND 別委任用）
--   3. 後方互換: ADMIN / DEPUTY_ADMIN に role_permissions 経由で天井登録
--
-- 既存スキーマとの整合性に関する注記（V9.156 と同じ方針）:
--   - permissions テーブルは BIGINT UNSIGNED の id + (name, display_name, scope) の構造で、
--     description / is_default カラムは存在しない。UUID も使わない。
--   - permission_groups にも is_default カラムは存在しない（target_role は持つ）。
--   - そのため、後方互換は permission_group_permissions 経由ではなく
--     既存 V9.116 / V9.156 と同じパターンで role_permissions に天井登録する方式を採用する:
--       - ADMIN: is_default=1（自動付与）
--       - DEPUTY_ADMIN: is_default=0（天井のみ。理事長が permission_groups 経由で個別付与）
--   これにより、Phase 5 第二陣で残高操作認可を
--   roleService.hasPermission(..., "POINT_CARD_BALANCE_OPERATE" / "..._REFUND")
--   へ切り替えても、現状の ADMIN は無変更で残高操作可能を維持できる（後方互換 100%）。

-- ============================================================================
-- 1. POINT_CARD_BALANCE_OPERATE Permission（CHARGE / SPENT 用）
-- ============================================================================
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
VALUES ('POINT_CARD_BALANCE_OPERATE', 'F18 残高操作（チャージ・利用）', 'ORGANIZATION', NOW(), NOW());

-- ============================================================================
-- 2. POINT_CARD_BALANCE_REFUND Permission（REFUND 別委任用）
-- ============================================================================
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
VALUES ('POINT_CARD_BALANCE_REFUND', 'F18 残高返金', 'ORGANIZATION', NOW(), NOW());

-- ============================================================================
-- 3. 後方互換: role_permissions に天井登録
-- ============================================================================
-- 3-1. ADMIN: is_default=1 で自動付与（現状の残高操作権限 ADMIN/DEPUTY_ADMIN を維持）
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.name IN ('POINT_CARD_BALANCE_OPERATE', 'POINT_CARD_BALANCE_REFUND');

-- 3-2. DEPUTY_ADMIN: is_default=0 で天井のみ登録
--      （ADMIN が permission_groups 経由で個別付与可能。初期状態では残高操作権限を持たないが、
--       既存実装が DEPUTY_ADMIN を許可していたため、運用上は permission_groups で
--       Phase 5 リリース時に明示付与する移行手順を取る想定）
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 0, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'DEPUTY_ADMIN'
  AND p.name IN ('POINT_CARD_BALANCE_OPERATE', 'POINT_CARD_BALANCE_REFUND');

-- 3-3. MEMBER / SUPPORTER / GUEST には天井エントリを作成しない（安全側設計）
