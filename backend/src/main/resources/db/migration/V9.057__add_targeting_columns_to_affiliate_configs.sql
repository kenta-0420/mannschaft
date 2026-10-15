-- affiliate_configsにターゲティング用カラムを追加
ALTER TABLE affiliate_configs
    MODIFY COLUMN provider VARCHAR(20) NOT NULL COMMENT 'AMAZON / RAKUTEN / GOOGLE_ADSENSE / GOOGLE_ADMOB / DIRECT',
    MODIFY COLUMN placement VARCHAR(30) NOT NULL COMMENT 'SIDEBAR_RIGHT / BANNER_FOOTER / BANNER_HEADER / IN_FEED',
    ADD COLUMN target_template VARCHAR(30) NULL COMMENT 'ターゲット組織テンプレート（NULLは全テンプレート対象）' AFTER display_priority,
    ADD COLUMN target_prefecture VARCHAR(20) NULL COMMENT 'ターゲット都道府県（NULLは全地域対象）' AFTER target_template,
    ADD COLUMN target_locale VARCHAR(10) NULL COMMENT 'ターゲットロケール（NULLは全言語対象）' AFTER target_prefecture,
    ADD INDEX idx_affiliate_configs_targeting (target_template, target_prefecture, target_locale);
