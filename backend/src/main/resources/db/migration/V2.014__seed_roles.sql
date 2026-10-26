-- 固定ロール初期データ投入
INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) VALUES
    ('SYSTEM_ADMIN', 'システム管理者', 1, 1, NOW(), NOW()),
    ('ADMIN', '管理者', 2, 0, NOW(), NOW()),
    ('DEPUTY_ADMIN', '副管理者', 3, 0, NOW(), NOW()),
    ('MEMBER', 'メンバー', 4, 0, NOW(), NOW()),
    ('SUPPORTER', 'サポーター', 5, 0, NOW(), NOW()),
    ('GUEST', 'ゲスト', 6, 0, NOW(), NOW());
