-- タイムラインダイジェスト自動生成設定テーブル
CREATE TABLE timeline_digest_configs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type VARCHAR(20) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    schedule_type VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    schedule_time TIME NULL DEFAULT '09:00:00',
    schedule_day_of_week TINYINT NULL DEFAULT 1,
    last_executed_at DATETIME NULL,
    digest_style VARCHAR(30) NOT NULL DEFAULT 'SUMMARY',
    auto_publish TINYINT(1) NOT NULL DEFAULT 0,
    style_presets JSON NULL,
    include_reactions TINYINT(1) NOT NULL DEFAULT 1,
    include_polls TINYINT(1) NOT NULL DEFAULT 1,
    include_diff_from_previous TINYINT(1) NOT NULL DEFAULT 0,
    min_posts_threshold INTEGER NOT NULL DEFAULT 3,
    max_posts_per_digest INTEGER NOT NULL DEFAULT 100,
    timezone VARCHAR(50) NOT NULL DEFAULT 'Asia/Tokyo',
    content_max_chars INTEGER NOT NULL DEFAULT 500,
    language VARCHAR(10) NOT NULL DEFAULT 'ja',
    custom_prompt_suffix TEXT NULL,
    auto_tag_ids JSON NULL,
    is_enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_by BIGINT UNSIGNED NOT NULL,
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT chk_tdc_scope_type CHECK (scope_type IN ('TEAM', 'ORGANIZATION')),
    CONSTRAINT chk_tdc_schedule_type CHECK (schedule_type IN ('MANUAL', 'DAILY', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT chk_tdc_digest_style CHECK (digest_style IN ('SUMMARY', 'NARRATIVE', 'HIGHLIGHTS', 'TEMPLATE')),
    CONSTRAINT chk_tdc_language CHECK (language IN ('ja', 'en'))
);
-- スコープ検索用
CREATE INDEX idx_tdc_scope ON timeline_digest_configs(scope_type, scope_id, deleted_at);
-- バッチ対象の検索
CREATE INDEX idx_tdc_schedule ON timeline_digest_configs(is_enabled, schedule_type, schedule_time);
