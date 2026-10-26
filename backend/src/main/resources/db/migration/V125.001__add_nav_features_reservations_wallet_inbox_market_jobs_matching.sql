-- F20.1: ナビゲーション項目マスタに6項目を追加
-- 追加項目: 予約確認 / ポイントカード / インボックス / 市 / 求人 / マッチング

INSERT INTO nav_features (`key`, label_key, icon, path, is_fixed, is_enabled, subscription_required, sort_order, mobile_visible) VALUES
    ('reservations', 'nav.reservations', 'pi pi-ticket',       '/my/reservations', FALSE, TRUE, FALSE,  82, TRUE),
    ('wallet',       'nav.wallet',       'pi pi-wallet',       '/wallet',          FALSE, TRUE, FALSE,  84, TRUE),
    ('inbox',        'nav.inbox',        'pi pi-inbox',        '/inbox',           FALSE, TRUE, FALSE,  25, TRUE),
    ('market',       'nav.market',       'pi pi-shopping-bag', '/market',          FALSE, TRUE, FALSE,  45, TRUE),
    ('jobs',         'nav.jobs',         'pi pi-briefcase',    '/jobs',            FALSE, TRUE, FALSE,  46, TRUE),
    ('matching',     'nav.matching',     'pi pi-bullseye',     '/matching',        FALSE, TRUE, FALSE,  47, TRUE);
