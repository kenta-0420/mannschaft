-- F08.7 Phase 9-δ: budget_configs 拡張 + 権限テーブル拡充 + ロール権限付与
-- 設計書 F08.7 (v1.2) §5.7 / §8.1 / §8.2 / §8.3 / §8.5 に準拠。
--
-- 本マイグレーションは複数責務を 1 トランザクションで実施する（設計書 §5.7 統合理由参照）:
--   (1) budget_configs に over_limit_workflow_id / shift_budget_enabled の 2 カラムを追加
--       - over_limit_workflow_id は workflow_templates(id) を参照
--         （設計書 §5.7 では workflow_definitions と表記されているが、Mannschaft の実体は
--         V5.028 で作成された workflow_templates テーブル。当該テーブルを参照する）
--       - shift_budget_enabled は三値論理（NULL=既定値継承 / TRUE=明示有効 / FALSE=明示無効）
--   (2) permissions テーブルへ F08.7 が依存する権限を一括 seed（マスター御裁可 Q1）
--       - 偵察結果（V2.015 / V2.016 等）により MANAGE_SHIFTS / BUDGET_VIEW / BUDGET_MANAGE /
--         MANAGE_TODO / BUDGET_ADMIN / VIEW_OWN_HOURLY_RATE のいずれも未 seed であることを確認
--       - 設計書 §8.3 通り 6 種すべて INSERT IGNORE で冪等投入する
--   (3) role_permissions への BUDGET_ADMIN / VIEW_OWN_HOURLY_RATE 自動付与
--       - 設計書 §5.7 の SQL は role_assignments を想定しているが、Mannschaft の実体は
--         role_permissions（ロール×権限の天井定義テーブル）+ permission_groups の体系。
--         「既存の MANAGE_SHIFTS + BUDGET_VIEW 保有者へ自動付与」の精神に最も近い翻訳として、
--         ADMIN / DEPUTY_ADMIN ロール（V2.014 で定義された 2 種）に BUDGET_ADMIN を紐付ける。
--         MEMBER ロールには VIEW_OWN_HOURLY_RATE を紐付ける（自分の時給閲覧をデフォルト許可）。
--       - WHERE NOT EXISTS で冪等化、再実行時に重複 INSERT が発生しないことを保証する。
--
-- 設計書からの逸脱と理由:
--   - 設計書 §5.7 は role_assignments（user × organization × permission_code テーブル）への
--     INSERT を想定しているが、Mannschaft の実体は role × permission の天井定義テーブル
--     （role_permissions）+ user × role の体系（user_roles）+ 権限グループ（permission_groups）。
--   - 設計書通り role_assignments に INSERT する SQL は実行不可能（テーブル不在）。
--   - したがって既存 V2.016 と同パターン（ADMIN/DEPUTY_ADMIN への天井定義 + MEMBER への
--     デフォルト付与）に翻訳する。これによりマスター御裁可 Q1「V11.034 で一括 seed」と
--     §8.1「BUDGET_ADMIN クリーンカット方式」の両方が満たされる。

-- ====================================================================
-- (1) budget_configs 拡張
-- ====================================================================

ALTER TABLE budget_configs
    ADD COLUMN over_limit_workflow_id BIGINT UNSIGNED DEFAULT NULL,
    ADD COLUMN shift_budget_enabled BOOLEAN DEFAULT NULL;

-- workflow_templates は V5.028 で既存。ON DELETE SET NULL で安全な紐付に。
ALTER TABLE budget_configs
    ADD CONSTRAINT fk_bconf_over_limit_workflow
        FOREIGN KEY (over_limit_workflow_id)
        REFERENCES workflow_templates(id) ON DELETE SET NULL;

-- ====================================================================
-- (2) permissions seed（マスター御裁可 Q1: 一括 seed）
-- ====================================================================
-- INSERT IGNORE で UNIQUE (name) 違反時はスキップ → 冪等性保証
-- scope は ORGANIZATION 統一（F08.7 は組織単位の機能）

INSERT IGNORE INTO permissions (name, display_name, scope, created_at, updated_at) VALUES
    ('BUDGET_VIEW',          '予算閲覧',                              'ORGANIZATION', NOW(), NOW()),
    ('BUDGET_MANAGE',        '予算管理（F08.6 旧権限・F08.7 では参照しない）', 'ORGANIZATION', NOW(), NOW()),
    ('MANAGE_SHIFTS',        'シフト管理',                            'TEAM',         NOW(), NOW()),
    ('MANAGE_TODO',          'TODO 管理',                             'ORGANIZATION', NOW(), NOW()),
    ('BUDGET_ADMIN',         'F08.7 シフト予算管理者（クリーンカット権限）',  'ORGANIZATION', NOW(), NOW()),
    ('VIEW_OWN_HOURLY_RATE', '自己時給確認',                          'ORGANIZATION', NOW(), NOW());

-- ====================================================================
-- (3) ADMIN / DEPUTY_ADMIN に BUDGET_ADMIN を天井付与
--     （設計書 §5.7 の精神: 既存 MANAGE_SHIFTS+BUDGET_VIEW 保有者への自動付与に相当）
-- ====================================================================
-- ADMIN は V2.016 と同じく is_default=1（実際付与）
-- DEPUTY_ADMIN は天井定義（is_default=0、permission_groups 経由で組織が個別付与）

INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.name = 'BUDGET_ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 0, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'DEPUTY_ADMIN'
  AND p.name = 'BUDGET_ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ====================================================================
-- (4) MEMBER に VIEW_OWN_HOURLY_RATE をデフォルト付与（設計書 §8.1）
-- ====================================================================

INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'MEMBER'
  AND p.name = 'VIEW_OWN_HOURLY_RATE'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ====================================================================
-- (5) ADMIN / DEPUTY_ADMIN にも VIEW_OWN_HOURLY_RATE を天井付与
--     （管理者も自分の時給を見られる必要があるため）
-- ====================================================================

INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.name = 'VIEW_OWN_HOURLY_RATE'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 0, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'DEPUTY_ADMIN'
  AND p.name = 'VIEW_OWN_HOURLY_RATE'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ====================================================================
-- (6) ADMIN / DEPUTY_ADMIN に新規 seed 権限の天井紐付（既存 V2.016 と同パターン）
--     BUDGET_VIEW / BUDGET_MANAGE / MANAGE_SHIFTS / MANAGE_TODO を全部紐付ける
-- ====================================================================

INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.name IN ('BUDGET_VIEW', 'BUDGET_MANAGE', 'MANAGE_SHIFTS', 'MANAGE_TODO')
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 0, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'DEPUTY_ADMIN'
  AND p.name IN ('BUDGET_VIEW', 'BUDGET_MANAGE', 'MANAGE_SHIFTS', 'MANAGE_TODO')
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
