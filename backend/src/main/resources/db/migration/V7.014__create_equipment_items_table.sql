-- F07.3 備品管理: 備品マスターテーブル
CREATE TABLE equipment_items (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id         BIGINT UNSIGNED NULL,
    organization_id BIGINT UNSIGNED NULL,
    name            VARCHAR(200)    NOT NULL,
    description     VARCHAR(500)    NULL,
    category        VARCHAR(100)    NULL,
    quantity        INT UNSIGNED    NOT NULL DEFAULT 1,
    assigned_quantity INT UNSIGNED  NOT NULL DEFAULT 0,
    status          ENUM('AVAILABLE', 'ALL_ASSIGNED', 'MAINTENANCE', 'RETIRED') NOT NULL DEFAULT 'AVAILABLE',
    is_consumable   BOOLEAN         NOT NULL DEFAULT FALSE,
    storage_location VARCHAR(200)   NULL,
    purchase_date   DATE            NULL,
    purchase_price  DECIMAL(10,2)   NULL,
    s3_key          VARCHAR(500)    NULL,
    qr_code         VARCHAR(100)    NOT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME        NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ei_team    FOREIGN KEY (team_id)         REFERENCES teams(id),
    CONSTRAINT fk_ei_org     FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT chk_ei_scope  CHECK (
        (team_id IS NOT NULL AND organization_id IS NULL)
        OR (team_id IS NULL AND organization_id IS NOT NULL)
    ),
    INDEX idx_ei_team_id          (team_id),
    INDEX idx_ei_organization_id  (organization_id),
    INDEX idx_ei_category_team    (team_id, category),
    INDEX idx_ei_category_org     (organization_id, category),
    INDEX idx_ei_status_team      (team_id, status),
    INDEX idx_ei_status_org       (organization_id, status),
    UNIQUE INDEX uq_ei_qr_code   (qr_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
