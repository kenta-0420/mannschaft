CREATE TABLE schedule_cross_refs (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source_schedule_id  BIGINT UNSIGNED NOT NULL,
    target_type         VARCHAR(20)     NOT NULL,
    target_id           BIGINT UNSIGNED NOT NULL,
    target_schedule_id  BIGINT UNSIGNED,
    invited_by          BIGINT UNSIGNED,
    status              VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    message             VARCHAR(500),
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    responded_at        DATETIME,

    PRIMARY KEY (id),
    INDEX idx_scr_source (source_schedule_id),
    INDEX idx_scr_target (target_type, target_id, status),
    INDEX idx_scr_target_schedule (target_schedule_id),
    UNIQUE KEY uq_scr_source_target (source_schedule_id, target_type, target_id),

    CONSTRAINT fk_scr_source FOREIGN KEY (source_schedule_id) REFERENCES schedules (id) ON DELETE CASCADE,
    CONSTRAINT fk_scr_target_schedule FOREIGN KEY (target_schedule_id) REFERENCES schedules (id) ON DELETE SET NULL,
    CONSTRAINT fk_scr_invited_by FOREIGN KEY (invited_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='クロスチーム・組織スケジュール招待';
