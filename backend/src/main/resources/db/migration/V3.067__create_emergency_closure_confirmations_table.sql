CREATE TABLE emergency_closure_confirmations (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    emergency_closure_id    BIGINT UNSIGNED NOT NULL,
    user_id                 BIGINT UNSIGNED NOT NULL,
    reservation_id          BIGINT UNSIGNED NOT NULL,
    appointment_at          DATETIME        NOT NULL,
    confirmed_at            DATETIME,
    reminder_sent_at        DATETIME,
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ecc_closure_user (emergency_closure_id, user_id),
    CONSTRAINT fk_ecc_closure
        FOREIGN KEY (emergency_closure_id) REFERENCES emergency_closures (id) ON DELETE CASCADE,
    CONSTRAINT fk_ecc_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_ecc_unconfirmed_appointment (confirmed_at, reminder_sent_at, appointment_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
