-- F20.1: ナビゲーションバーカスタマイズ & 機能管理

CREATE TABLE nav_features (
    `key`                 VARCHAR(50)    NOT NULL COMMENT 'ナビ項目識別キー（ケバブケース。例: shift-management）',
    label_key             VARCHAR(100)   NOT NULL COMMENT 'フロントエンド i18n キー（例: nav.shiftManagement）',
    icon                  VARCHAR(50)    NOT NULL COMMENT 'PrimeVue アイコンクラス（例: pi pi-table）',
    path                  VARCHAR(200)   NOT NULL COMMENT 'Nuxt ルートパス（例: /shift）',
    is_fixed              BOOLEAN        NOT NULL DEFAULT FALSE COMMENT 'TRUE = ユーザーによる非表示不可・削除不可・is_fixed変更不可',
    is_enabled            BOOLEAN        NOT NULL DEFAULT TRUE  COMMENT 'FALSE = 全ユーザーのナビから非表示',
    subscription_required BOOLEAN        NOT NULL DEFAULT FALSE COMMENT '将来課金フック（現フェーズはカラムのみ）',
    sort_order            INT            NOT NULL DEFAULT 0     COMMENT '表示順（昇順）',
    mobile_visible        BOOLEAN        NOT NULL DEFAULT TRUE  COMMENT 'FALSE = PCのみ表示',
    created_at            DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ナビゲーション項目マスタ（シスアド管理）';

CREATE INDEX idx_nav_features_enabled_sort ON nav_features (is_enabled, sort_order);

INSERT INTO nav_features (`key`, label_key, icon, path, is_fixed, is_enabled, subscription_required, sort_order, mobile_visible) VALUES
    ('calendar',         'nav.calendar',        'pi pi-calendar',        '/calendar',  TRUE,  TRUE,  FALSE,  20, TRUE),
    ('settings',         'nav.settings',        'pi pi-cog',             '/settings',  TRUE,  TRUE,  FALSE, 100, TRUE),
    ('todo',             'nav.todo',            'pi pi-check-square',    '/todos',     FALSE, TRUE,  FALSE,  30, TRUE),
    ('shift-management', 'nav.shiftManagement', 'pi pi-table',           '/shift',     FALSE, TRUE,  FALSE,  40, TRUE),
    ('timeline',         'nav.timeline',        'pi pi-comments',        '/timeline',  FALSE, TRUE,  FALSE,  50, TRUE),
    ('chat',             'nav.chat',            'pi pi-comment',         '/chat',      FALSE, TRUE,  FALSE,  60, TRUE),
    ('my-shift',         'nav.myShift',         'pi pi-clock',           '/my/shift',  FALSE, TRUE,  FALSE,  70, TRUE),
    ('my-page',          'nav.myPage',          'pi pi-user',            '/my',        FALSE, TRUE,  FALSE,  80, TRUE),
    ('qa',               'nav.qa',              'pi pi-question-circle', '/help/qa',   FALSE, TRUE,  FALSE,  90, TRUE),
    ('villages',         'village.title',       'pi pi-th-large',        '/villages',  FALSE, TRUE,  FALSE,  35, TRUE),
    ('blog',             'nav.blog',            'pi pi-book',            '/blog',      FALSE, TRUE,  FALSE,  55, TRUE);

CREATE TABLE user_nav_settings (
    user_id         BIGINT         NOT NULL COMMENT 'users.id（FK制約なし。クロスドメインFK禁止原則）',
    hidden_nav_keys JSON           NOT NULL COMMENT '非表示にしたnav_features.keyの配列。例: ["todo","my-shift"]',
    updated_at      DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ユーザーごとのナビ非表示設定（1ユーザー1行）';
