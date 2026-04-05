-- F09.3 サブリース申請
CREATE TABLE parking_sublease_applications (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    sublease_id  BIGINT UNSIGNED NOT NULL,
    user_id      BIGINT UNSIGNED NOT NULL,
    vehicle_id   BIGINT UNSIGNED NOT NULL,
    message      VARCHAR(500)    NULL,
    status       ENUM('PENDING','APPROVED','REJECTED','CANCELLED') NOT NULL DEFAULT 'PENDING',
    decided_at   DATETIME        NULL,
    created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_psla_sublease_user (sublease_id, user_id, status),
    INDEX idx_psla_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
