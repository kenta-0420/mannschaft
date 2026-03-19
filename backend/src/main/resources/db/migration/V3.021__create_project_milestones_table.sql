-- F02.3: マイルストーン（中間目標）テーブル
CREATE TABLE project_milestones (
    id           BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    project_id   BIGINT UNSIGNED  NOT NULL,
    title        VARCHAR(200)     NOT NULL,
    due_date     DATE             NULL,
    sort_order   SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    is_completed BOOLEAN          NOT NULL DEFAULT FALSE,
    completed_at DATETIME         NULL,
    created_at   DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_pm_project_order (project_id, sort_order),
    UNIQUE KEY uq_pm_project_title (project_id, title),
    CONSTRAINT fk_pm_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
