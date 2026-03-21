CREATE TABLE shift_schedules (
    id                          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    team_id                     BIGINT UNSIGNED  NOT NULL,
    title                       VARCHAR(200)     NOT NULL,
    period_type                 VARCHAR(20)      NOT NULL DEFAULT 'WEEKLY',
    start_date                  DATE             NOT NULL,
    end_date                    DATE             NOT NULL,
    status                      VARCHAR(20)      NOT NULL DEFAULT 'DRAFT',
    request_deadline            DATETIME,
    note                        TEXT,
    created_by                  BIGINT UNSIGNED,
    published_at                DATETIME,
    published_by                BIGINT UNSIGNED,
    is_reminder_sent            BOOLEAN          NOT NULL DEFAULT FALSE,
    is_low_submission_alerted   BOOLEAN          NOT NULL DEFAULT FALSE,
    last_auto_transition_at     DATETIME,
    version                     BIGINT           NOT NULL DEFAULT 0,
    created_at                  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at                  DATETIME,

    PRIMARY KEY (id),
    INDEX idx_ss_team_start (team_id, start_date DESC),
    INDEX idx_ss_team_status (team_id, status),

    CONSTRAINT fk_ss_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_ss_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_ss_published_by FOREIGN KEY (published_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='シフトスケジュールマスター';
