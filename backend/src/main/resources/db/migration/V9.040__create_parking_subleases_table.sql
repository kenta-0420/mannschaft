-- F09.3 サブリース（又貸し）
CREATE TABLE parking_subleases (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    space_id                BIGINT UNSIGNED NOT NULL,
    assignment_id           BIGINT UNSIGNED NOT NULL,
    offered_by              BIGINT UNSIGNED NOT NULL,
    title                   VARCHAR(100)    NOT NULL,
    description             TEXT            NULL,
    price_per_month         DECIMAL(10,0)   NOT NULL,
    payment_method          ENUM('DIRECT','STRIPE') NOT NULL DEFAULT 'DIRECT',
    available_from          DATE            NOT NULL,
    available_to            DATE            NULL,
    status                  ENUM('OPEN','MATCHED','CANCELLED') NOT NULL DEFAULT 'OPEN',
    matched_application_id  BIGINT UNSIGNED NULL,
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at              DATETIME        NULL,
    PRIMARY KEY (id),
    INDEX idx_psl_scope (space_id),
    INDEX idx_psl_offered_by (offered_by),
    UNIQUE KEY uq_psl_space_active (space_id, status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
