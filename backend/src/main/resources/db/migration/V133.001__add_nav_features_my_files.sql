-- F13: ナビゲーション項目マスタにマイファイルを追加
-- 個人ファイル管理ページ（/my/files）への導線

INSERT INTO nav_features (`key`, label_key, icon, path, is_fixed, is_enabled, subscription_required, sort_order, mobile_visible) VALUES
    ('my-files', 'nav.myFiles', 'pi pi-folder', '/my/files', FALSE, TRUE, FALSE, 48, TRUE);
