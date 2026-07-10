-- =====================================================================
-- F20.1 課金・エンタイトルメント基盤: マスタ4表（マスタ例外・自然キー）
-- =====================================================================
-- 設計書: docs/features/F20.1_entitlement_billing/01_data_model.md §2
-- CLAUDE.md 原則6 の例外区分（全テナント共通の参照データ・シスアド運用のみ書込）。
-- クロスドメインFKは張らない（scope_id 等は論理参照。本表はドメイン内で完結する）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- feature_catalog: 機能キーの台帳（分類・アドオン可否・単価・i18nキー）
-- ---------------------------------------------------------------------
CREATE TABLE feature_catalog (
    feature_key VARCHAR(64) NOT NULL COMMENT '機能キー（英小文字ドット区切り。例: reservation.notification_recipients_extended）',
    category VARCHAR(8) NOT NULL COMMENT 'INTERNAL=内向き機能（無料枠広い方針）/ REVENUE=収益機能（区分問わず有料）',
    addon_available BOOLEAN NOT NULL DEFAULT FALSE COMMENT '単品アドオン契約可か',
    addon_price_jpy INT UNSIGNED NULL COMMENT 'アドオン月額（円）。NULL=未定（実額はベータ終了時決定・機構のみ）',
    free_for_nonprofit BOOLEAN NOT NULL DEFAULT FALSE COMMENT '非営利スコープに無料開放するか（INTERNAL の無料枠の機構・値は運用設定）',
    display_name_key VARCHAR(128) NOT NULL COMMENT 'i18n 表示名キー（例: billing.features.reservation_notification_recipients_extended.name）',
    description_key VARCHAR(128) NOT NULL COMMENT 'i18n 説明キー（同 .description）',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '表示順',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'false=カタログ非表示＋isEntitled は常に false（fail-safe）',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (feature_key),
    CONSTRAINT chk_fc_category CHECK (category IN ('INTERNAL','REVENUE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='機能カタログ（マスタ例外・自然キー）';

-- ---------------------------------------------------------------------
-- plans: 3プランの提示定義
-- ---------------------------------------------------------------------
CREATE TABLE plans (
    plan_key VARCHAR(32) NOT NULL COMMENT 'FREE / BASIC / FULL',
    display_name_key VARCHAR(128) NOT NULL COMMENT 'i18n キー（例: billing.plans.full.name）',
    description_key VARCHAR(128) NOT NULL COMMENT 'i18n キー（同 .description）',
    base_monthly_price_jpy INT UNSIGNED NULL COMMENT '基準月額（円・USER スコープ/バンド未定義時）。NULL=未定。FULL=2000 想定（実額はベータ終了時決定）',
    sort_order INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'false=新規契約不可（既存契約は維持）',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (plan_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提示プラン（マスタ例外・自然キー）';

-- ---------------------------------------------------------------------
-- plan_features: プラン→機能の展開表（同一ドメイン内のためFK+CASCADE可）
-- ---------------------------------------------------------------------
CREATE TABLE plan_features (
    plan_key VARCHAR(32) NOT NULL,
    feature_key VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (plan_key, feature_key),
    CONSTRAINT fk_pf_plan FOREIGN KEY (plan_key) REFERENCES plans (plan_key) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='プラン→機能の展開表（マスタ例外）。fk は同一ドメイン内ゆえ CASCADE 可';

-- ---------------------------------------------------------------------
-- plan_price_bands: 人数バンド別単価（機構のみ・実額NULL可）
-- ---------------------------------------------------------------------
CREATE TABLE plan_price_bands (
    plan_key VARCHAR(32) NOT NULL,
    scope_kind VARCHAR(8) NOT NULL COMMENT 'TEAM / ORG（USER は base_monthly_price_jpy を使用しバンド無し）',
    band_no TINYINT UNSIGNED NOT NULL COMMENT 'バンド番号（1〜・昇順）',
    min_members INT UNSIGNED NOT NULL COMMENT 'アクティブ人数下限（この値以上）',
    max_members INT UNSIGNED NULL COMMENT 'アクティブ人数上限（この値以下）。NULL=無制限（最終バンド）',
    monthly_price_jpy INT UNSIGNED NULL COMMENT '月額（円）。NULL=未定（実額はベータ終了時に実データで決定）',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (plan_key, scope_kind, band_no),
    CONSTRAINT fk_ppb_plan FOREIGN KEY (plan_key) REFERENCES plans (plan_key) ON DELETE CASCADE,
    CONSTRAINT chk_ppb_scope CHECK (scope_kind IN ('TEAM','ORG'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人数バンド別単価（マスタ例外・機構のみ）';
