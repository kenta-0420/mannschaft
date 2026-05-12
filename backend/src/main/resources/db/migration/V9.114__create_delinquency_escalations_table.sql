-- F09.15 S1-A: delinquency_escalations（5 段階エスカレーション）
-- 設計書: docs/features/F09.15_resident_succession_support.md §5.7
--
-- 1 居住者 1 エスカ。STAGE_1〜5 の段階別到達日時を保持し、
-- バッチ（@Scheduled）が次段階への進行判定に使用する。

CREATE TABLE delinquency_escalations (
    id                       BINARY(16) NOT NULL,
    organization_id          BIGINT UNSIGNED NOT NULL,
    dwelling_unit_id         BIGINT UNSIGNED NOT NULL,
    resident_registry_id     BIGINT UNSIGNED NOT NULL,
    current_stage            VARCHAR(30) NOT NULL DEFAULT 'STAGE_1_REMINDER',
    delinquency_started_at   DATE NOT NULL,
    last_contact_attempt_at  DATETIME(6) NULL,
    stage_1_completed_at     DATETIME(6) NULL,
    stage_2_completed_at     DATETIME(6) NULL,
    stage_3_completed_at     DATETIME(6) NULL,
    stage_4_completed_at     DATETIME(6) NULL,
    stage_5_completed_at     DATETIME(6) NULL,
    frozen_at                DATETIME(6) NULL,
    frozen_reason            TEXT NULL,
    resolved_at              DATETIME(6) NULL,
    resolved_reason          VARCHAR(50) NULL,
    deleted_at               DATETIME(6) NULL,
    created_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- エスカレーション段階 enum 制約（設計書 §4 エスカレーション段階 enum）
    CONSTRAINT chk_de_current_stage CHECK (current_stage IN (
        'STAGE_1_REMINDER',
        'STAGE_2_EMERGENCY_CONTACT',
        'STAGE_3_WATCHER_VISIT',
        'STAGE_4_DEATH_SUSPECTED',
        'STAGE_5_LEGAL_PREP'
    ))
);

-- 1 居住者 1 エスカ（論理削除を考慮した複合 UNIQUE）
CREATE UNIQUE INDEX uq_de_resident ON delinquency_escalations (resident_registry_id, deleted_at);

CREATE INDEX idx_de_org ON delinquency_escalations (organization_id, deleted_at);
CREATE INDEX idx_de_dwelling ON delinquency_escalations (dwelling_unit_id);
-- バッチ進行用（current_stage + frozen_at + resolved_at で進行対象を絞り込む）
CREATE INDEX idx_de_stage ON delinquency_escalations (current_stage, frozen_at, resolved_at);
