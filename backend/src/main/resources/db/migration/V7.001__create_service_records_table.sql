-- =============================================
-- F07.1 サービス履歴: service_records テーブル
-- =============================================
CREATE TABLE service_records (
    id             BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    team_id        BIGINT UNSIGNED  NOT NULL,
    member_user_id BIGINT UNSIGNED  NOT NULL,
    staff_user_id  BIGINT UNSIGNED  NULL,
    service_date   DATE             NOT NULL,
    title          VARCHAR(200)     NOT NULL,
    note           TEXT             NULL,
    duration_minutes SMALLINT UNSIGNED NULL,
    status         ENUM('DRAFT', 'CONFIRMED') NOT NULL DEFAULT 'DRAFT',
    created_at     DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at     DATETIME         NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_sr_team       FOREIGN KEY (team_id)        REFERENCES teams(id),
    CONSTRAINT fk_sr_member     FOREIGN KEY (member_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_sr_staff      FOREIGN KEY (staff_user_id)  REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_sr_team_id       (team_id),
    INDEX idx_sr_member        (member_user_id),
    INDEX idx_sr_team_member   (team_id, member_user_id),
    INDEX idx_sr_service_date  (team_id, service_date DESC),
    INDEX idx_sr_status        (team_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
