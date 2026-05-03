-- F14.1 代理入力・非デジタル住民対応: 代理入力関連権限の追加
-- 権限はORGANIZATIONスコープ（組合単位で付与）
INSERT INTO permissions (name, display_name, scope, created_at, updated_at) VALUES
    ('PROXY_INPUT_EXECUTE',   '代理入力実行',   'ORGANIZATION', NOW(), NOW()),
    ('PROXY_CONSENT_APPROVE', '代理同意書承認', 'ORGANIZATION', NOW(), NOW());

-- ADMIN: 両権限をデフォルト付与（is_default=1）
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.name IN ('PROXY_INPUT_EXECUTE', 'PROXY_CONSENT_APPROVE');

-- DEPUTY_ADMIN: 両権限を天井定義（is_default=0、permission_groups経由で選別付与）
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 0, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'DEPUTY_ADMIN'
  AND p.name IN ('PROXY_INPUT_EXECUTE', 'PROXY_CONSENT_APPROVE');

-- SUPPORTER: PROXY_INPUT_EXECUTEのみ天井定義（条件付き: Service層でuser_care_linksを確認）
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 0, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'SUPPORTER'
  AND p.name = 'PROXY_INPUT_EXECUTE';
