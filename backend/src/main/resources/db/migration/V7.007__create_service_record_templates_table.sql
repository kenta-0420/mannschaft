-- =============================================
-- F07.1 サービス履歴: service_record_templates テーブル
-- =============================================
CREATE TABLE service_record_templates (
    id                       BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    team_id                  BIGINT UNSIGNED  NULL,
    organization_id          BIGINT UNSIGNED  NULL,
    name                     VARCHAR(100)     NOT NULL,
    title_template           VARCHAR(200)     NULL,
    note_template            TEXT             NULL,
    default_duration_minutes SMALLINT UNSIGNED NULL,
    sort_order               INT              NOT NULL DEFAULT 0,
    created_by               BIGINT UNSIGNED  NULL,
    created_at               DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at               DATETIME         NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_srt_team FOREIGN KEY (team_id)          REFERENCES teams(id),
    CONSTRAINT fk_srt_org  FOREIGN KEY (organization_id)  REFERENCES organizations(id),
    CONSTRAINT fk_srt_user FOREIGN KEY (created_by)       REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_srt_scope CHECK (
        (team_id IS NOT NULL AND organization_id IS NULL)
        OR (team_id IS NULL AND organization_id IS NOT NULL)
    ),
    INDEX idx_srt_team_sort (team_id, sort_order),
    INDEX idx_srt_org_sort  (organization_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
