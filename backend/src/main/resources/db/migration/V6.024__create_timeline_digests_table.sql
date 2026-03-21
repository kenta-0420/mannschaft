-- タイムラインダイジェスト履歴テーブル
CREATE TABLE timeline_digests (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    config_id BIGINT UNSIGNED NULL,
    scope_type VARCHAR(20) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    period_start DATETIME NOT NULL,
    period_end DATETIME NOT NULL,
    post_count INTEGER NULL,
    digest_style VARCHAR(30) NOT NULL,
    generated_title VARCHAR(200) NULL,
    generated_body TEXT NULL,
    generated_excerpt VARCHAR(500) NULL,
    ai_model VARCHAR(50) NULL,
    ai_input_tokens INTEGER NULL,
    ai_output_tokens INTEGER NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'GENERATING',
    blog_post_id BIGINT UNSIGNED NULL,
    generating_timeout_at DATETIME NULL,
    source_post_ids JSON NULL,
    error_message TEXT NULL,
    triggered_by BIGINT UNSIGNED NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_td_config FOREIGN KEY (config_id) REFERENCES timeline_digest_configs(id),
    CONSTRAINT chk_td_scope_type CHECK (scope_type IN ('TEAM', 'ORGANIZATION')),
    CONSTRAINT chk_td_digest_style CHECK (digest_style IN ('SUMMARY', 'NARRATIVE', 'HIGHLIGHTS', 'TEMPLATE')),
    CONSTRAINT chk_td_status CHECK (status IN ('GENERATING', 'GENERATED', 'PUBLISHED', 'DISCARDED', 'FAILED'))
);
-- スコープ別のダイジェスト履歴
CREATE INDEX idx_td_scope ON timeline_digests(scope_type, scope_id, created_at DESC);
-- ステータス別検索
CREATE INDEX idx_td_status ON timeline_digests(status);
-- 期間重複チェック
CREATE INDEX idx_td_period ON timeline_digests(scope_type, scope_id, period_start, period_end);
