-- F09.16 S1-B: residence-status ドメイン
-- resident_activity_snapshots（日次集計・30 日ローテ）
-- CLAUDE.md 原則 6 適用（新規テーブル UUIDv7 主キー）
-- クロスドメイン FK 禁止のため dwelling_unit_id / resident_registry_id / subject_user_id は INDEX のみ
CREATE TABLE resident_activity_snapshots (
    id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    dwelling_unit_id BIGINT UNSIGNED NOT NULL,
    resident_registry_id BIGINT UNSIGNED NOT NULL,
    subject_user_id BIGINT UNSIGNED NOT NULL,
    snapshot_date DATE NOT NULL,
    activity_score_total SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    activity_breakdown_json JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ras_user_date (subject_user_id, snapshot_date, deleted_at),
    INDEX idx_ras_resident_date (resident_registry_id, snapshot_date DESC),
    INDEX idx_ras_org (organization_id, deleted_at),
    INDEX idx_ras_dwelling (dwelling_unit_id),
    INDEX idx_ras_snapshot_date (snapshot_date)
);
