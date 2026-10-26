-- F03.9: 時間割管理 - 組織レベル時限定義テンプレート
CREATE TABLE timetable_period_templates (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    period_number   TINYINT UNSIGNED NOT NULL COMMENT '時限番号（1〜15）。表示順もこの値で決定',
    label           VARCHAR(50)     NOT NULL COMMENT '表示名（例: 1限目, 朝の会, 昼休み）',
    start_time      TIME            NOT NULL,
    end_time        TIME            NOT NULL,
    is_break        BOOLEAN         NOT NULL DEFAULT FALSE COMMENT '休憩枠フラグ（TRUE=昼休み等。コマ割当対象外）',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_tpt_org_period (organization_id, period_number),
    INDEX idx_tpt_org (organization_id),
    CONSTRAINT fk_tpt_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT chk_tpt_time_order CHECK (start_time < end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
