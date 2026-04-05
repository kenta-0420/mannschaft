-- =============================================
-- F07.1 サービス履歴: service_record_fields テーブル
-- =============================================
CREATE TABLE service_record_fields (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id     BIGINT UNSIGNED NOT NULL,
    field_name  VARCHAR(100)    NOT NULL,
    field_type  ENUM('TEXT', 'NUMBER', 'DATE', 'SELECT', 'MULTISELECT', 'CHECKBOX') NOT NULL,
    description VARCHAR(500)    NULL,
    options     JSON            NULL,
    is_required BOOLEAN         NOT NULL DEFAULT FALSE,
    sort_order  INT             NOT NULL DEFAULT 0,
    is_active   BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_srf_team FOREIGN KEY (team_id) REFERENCES teams(id),
    INDEX idx_srf_team_sort (team_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
