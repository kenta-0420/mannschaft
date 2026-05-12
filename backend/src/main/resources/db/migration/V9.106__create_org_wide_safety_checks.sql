-- F09.16 S1-B: residence-status ドメイン
-- org_wide_safety_checks（管理組合横展開のラッパ）
-- safety_check_id は F03.6 safety_checks への弱参照（FKなし・INDEXのみ）
CREATE TABLE org_wide_safety_checks (
    id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    safety_check_id BIGINT UNSIGNED NOT NULL,
    triggered_by BIGINT UNSIGNED NOT NULL,
    triggered_at DATETIME(6) NOT NULL,
    trigger_reason VARCHAR(200) NULL,
    closed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_owsc_safety (safety_check_id),
    INDEX idx_owsc_org_triggered (organization_id, triggered_at DESC),
    INDEX idx_owsc_org (organization_id, deleted_at),
    INDEX idx_owsc_triggered_by (triggered_by)
);
