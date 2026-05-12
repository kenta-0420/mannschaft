-- F08.8 Phase 1: 修繕シミュレーションシナリオ
-- 改ざん検出 content_sha256・楽観ロック・議案変換用 locked_at をサポート。
-- locked_at UPDATE 拒否トリガは V13.046 と一緒に DELIMITER ブロックで定義する設計だが、
-- 同テーブル単体のトリガとして本マイグレーションで定義する。
CREATE TABLE repair_simulation_scenarios (
    id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(200) NULL,
    description TEXT NULL,
    params_json JSON NOT NULL,
    computed_summary_json JSON NOT NULL,
    engine_version VARCHAR(20) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    baseline_at DATETIME NOT NULL,
    locked_at DATETIME NULL,
    published_announcement_id BIGINT UNSIGNED NULL,
    pinned_corkboard_id BIGINT UNSIGNED NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_rss_scope_type CHECK (scope_type IN ('ORGANIZATION','TEAM'))
);

CREATE INDEX idx_rss_organization_id ON repair_simulation_scenarios (organization_id);
CREATE INDEX idx_rss_scope ON repair_simulation_scenarios (scope_type, scope_id, deleted_at, created_at DESC);
CREATE INDEX idx_rss_locked ON repair_simulation_scenarios (scope_type, scope_id, locked_at);
CREATE INDEX idx_rss_published ON repair_simulation_scenarios (published_announcement_id);
CREATE UNIQUE INDEX uq_rss_content_sha ON repair_simulation_scenarios (scope_type, scope_id, content_sha256);

-- 一旦ロックされたシナリオは UPDATE 不可（議案変換時の不変保証）
-- flyway-mysql プラグインは BEGIN ... END ブロックを自動認識するため DELIMITER 命令は不要。
CREATE TRIGGER trg_rss_block_update_after_lock
BEFORE UPDATE ON repair_simulation_scenarios
FOR EACH ROW
BEGIN
    IF OLD.locked_at IS NOT NULL AND NEW.deleted_at IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'F08.8: Cannot update a locked repair_simulation_scenario (locked_at is set).';
    END IF;
END;
