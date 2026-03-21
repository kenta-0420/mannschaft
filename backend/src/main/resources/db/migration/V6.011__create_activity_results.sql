-- F06.4: 活動記録テーブル
CREATE TABLE activity_results (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id            BIGINT UNSIGNED NULL,
    organization_id    BIGINT UNSIGNED NULL,
    template_id        BIGINT UNSIGNED NULL,
    title              VARCHAR(200) NOT NULL,
    description        TEXT NULL,
    activity_date      DATE NOT NULL,
    location           VARCHAR(200) NULL,
    visibility         VARCHAR(30) NOT NULL DEFAULT 'MEMBERS_ONLY',
    cover_image_url    VARCHAR(500) NULL,
    schedule_event_id  BIGINT UNSIGNED NULL,
    participant_count  INT UNSIGNED NOT NULL DEFAULT 0,
    view_count         INT UNSIGNED NOT NULL DEFAULT 0,
    created_by         BIGINT UNSIGNED NULL,
    created_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at         DATETIME NULL,
    PRIMARY KEY (id),
    INDEX idx_ar_team_date (team_id, activity_date DESC),
    INDEX idx_ar_org_date (organization_id, activity_date DESC),
    INDEX idx_ar_created_by (created_by),
    INDEX idx_ar_template (template_id),
    UNIQUE KEY uq_ar_schedule_event (schedule_event_id),
    CONSTRAINT chk_ar_scope CHECK (
        (team_id IS NOT NULL AND organization_id IS NULL)
        OR (team_id IS NULL AND organization_id IS NOT NULL)
    ),
    CONSTRAINT fk_ar_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_ar_org FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_ar_template FOREIGN KEY (template_id) REFERENCES activity_templates (id) ON DELETE SET NULL,
    CONSTRAINT fk_ar_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
