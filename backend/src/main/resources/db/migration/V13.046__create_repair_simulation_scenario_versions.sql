-- F08.8 Phase 1: 修繕シミュレーションシナリオの不変バージョン
-- 議案変換時に scenario の完全スナップショットを保存。INSERT のみ、UPDATE/DELETE はトリガで拒否。
CREATE TABLE repair_simulation_scenario_versions (
    id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    scenario_id BINARY(16) NOT NULL,
    version_no INT UNSIGNED NOT NULL,
    params_snapshot JSON NOT NULL,
    computed_summary_snapshot JSON NOT NULL,
    engine_version VARCHAR(20) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    proposed_resolution_no VARCHAR(100) NULL,
    locked_by BIGINT UNSIGNED NOT NULL,
    locked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_rssv_scenario FOREIGN KEY (scenario_id)
        REFERENCES repair_simulation_scenarios (id) ON DELETE RESTRICT
);

CREATE INDEX idx_rssv_organization_id ON repair_simulation_scenario_versions (organization_id);
CREATE UNIQUE INDEX uq_rssv_scenario_version ON repair_simulation_scenario_versions (scenario_id, version_no);
CREATE INDEX idx_rssv_locked ON repair_simulation_scenario_versions (scenario_id, locked_at DESC);

-- 不変保証: UPDATE/DELETE を常に拒否
CREATE TRIGGER trg_rssv_block_update
BEFORE UPDATE ON repair_simulation_scenario_versions
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'F08.8: repair_simulation_scenario_versions are immutable (UPDATE forbidden).';
END;

CREATE TRIGGER trg_rssv_block_delete
BEFORE DELETE ON repair_simulation_scenario_versions
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'F08.8: repair_simulation_scenario_versions are immutable (DELETE forbidden).';
END;
