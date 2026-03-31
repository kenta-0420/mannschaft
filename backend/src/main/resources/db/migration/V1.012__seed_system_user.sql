-- システム管理者ユーザー（id=1）を作成する。
-- 各種シードデータの created_by 参照先として必要。
INSERT INTO users (id, email, password_hash, last_name, first_name, display_name, status, created_at, updated_at)
VALUES (1, 'system@mannschaft.local', NULL, 'システム', '管理者', 'システム管理者', 'ACTIVE', NOW(), NOW());
