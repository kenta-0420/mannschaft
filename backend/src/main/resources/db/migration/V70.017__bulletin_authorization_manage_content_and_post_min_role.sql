-- F05.1 掲示板: 認可硬化（MANAGE_CONTENT 権限のシード + post_min_role の正規化）
--
-- 1. MANAGE_CONTENT パーミッション（コンテンツ管理: カテゴリ CRUD・ピン/ロック/アーカイブ）を追加。
--    設計書 F05.1 §2 で DEPUTY_ADMIN は MANAGE_CONTENT 権限を持つ場合のみコンテンツ管理を行える。
-- 2. ADMIN へ既定付与（is_default=1）。
-- 3. DEPUTY_ADMIN へ既定付与（is_default=1）。設計書 §2 の「MANAGE_CONTENT 権限を持つ場合」の
--    デフォルト体系として DEPUTY_ADMIN に既定で付与する（個別剥奪は permission_groups で制御）。
-- 4. bulletin_categories.post_min_role の DEFAULT を 'MEMBER_PLUS'（権限ロールに実在しない誤値）から
--    設計書 §3 の正値 'MEMBER' に変更し、既存の 'MEMBER_PLUS' 行を 'MEMBER' へ是正する。

-- 1. MANAGE_CONTENT パーミッション追加（既存なら何もしない: 冪等）
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
SELECT 'MANAGE_CONTENT', 'コンテンツ管理', 'TEAM', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'MANAGE_CONTENT');

-- 2. ADMIN へ既定付与（is_default=1）
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.name = 'MANAGE_CONTENT'
  AND NOT EXISTS (
    SELECT 1 FROM role_permissions rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 3. DEPUTY_ADMIN へ既定付与（is_default=1）
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'DEPUTY_ADMIN'
  AND p.name = 'MANAGE_CONTENT'
  AND NOT EXISTS (
    SELECT 1 FROM role_permissions rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 4. post_min_role の DEFAULT を 'MEMBER' に変更
ALTER TABLE bulletin_categories
    MODIFY COLUMN post_min_role VARCHAR(20) NOT NULL DEFAULT 'MEMBER';

-- 既存の誤値 'MEMBER_PLUS' を 'MEMBER' へ是正
UPDATE bulletin_categories
SET post_min_role = 'MEMBER'
WHERE post_min_role = 'MEMBER_PLUS';
