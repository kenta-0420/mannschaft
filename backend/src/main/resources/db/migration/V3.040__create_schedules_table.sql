CREATE TABLE schedules (
    id                BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    team_id           BIGINT UNSIGNED  NULL,
    organization_id   BIGINT UNSIGNED  NULL,
    user_id           BIGINT UNSIGNED  NULL,
    title             VARCHAR(200)     NOT NULL,
    description       TEXT,
    location          VARCHAR(300),
    start_at          DATETIME         NOT NULL,
    end_at            DATETIME,
    all_day           BOOLEAN          NOT NULL DEFAULT FALSE,
    event_type        VARCHAR(20)      NOT NULL DEFAULT 'OTHER',
    visibility        VARCHAR(20)      NOT NULL DEFAULT 'MEMBERS_ONLY',
    min_view_role     VARCHAR(20)      NOT NULL DEFAULT 'MEMBER_PLUS',
    min_response_role VARCHAR(20)      NOT NULL DEFAULT 'MEMBER_PLUS',
    status            VARCHAR(20)      NOT NULL DEFAULT 'SCHEDULED',
    attendance_required BOOLEAN        NOT NULL DEFAULT FALSE,
    attendance_status VARCHAR(20)      NOT NULL DEFAULT 'READY',
    attendance_deadline DATETIME,
    comment_option    VARCHAR(20)      NOT NULL DEFAULT 'OPTIONAL',
    parent_schedule_id BIGINT UNSIGNED,
    recurrence_rule   JSON,
    is_exception      BOOLEAN          NOT NULL DEFAULT FALSE,
    color             VARCHAR(7),
    google_calendar_event_id VARCHAR(255),
    created_by        BIGINT UNSIGNED,
    created_at        DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at        DATETIME,

    PRIMARY KEY (id),
    UNIQUE KEY uq_sch_parent_start (parent_schedule_id, start_at),
    INDEX idx_sch_team_start (team_id, start_at),
    INDEX idx_sch_org_start (organization_id, start_at),
    INDEX idx_sch_user_start (user_id, start_at),
    INDEX idx_sch_parent (parent_schedule_id),
    INDEX idx_sch_status_end_at (status, end_at),
    INDEX idx_sch_google_calendar (google_calendar_event_id),

    CONSTRAINT fk_sch_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_sch_org FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_sch_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_sch_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_sch_parent FOREIGN KEY (parent_schedule_id) REFERENCES schedules (id) ON DELETE RESTRICT,

    CONSTRAINT chk_schedule_scope CHECK (
        (team_id IS NOT NULL AND organization_id IS NULL AND user_id IS NULL) OR
        (team_id IS NULL AND organization_id IS NOT NULL AND user_id IS NULL) OR
        (team_id IS NULL AND organization_id IS NULL AND user_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='スケジュール・イベントマスター';
