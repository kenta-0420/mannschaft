-- F09.5 共用施設予約: 予約支払い
CREATE TABLE facility_booking_payments (
    id                        BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    booking_id                BIGINT UNSIGNED NOT NULL,
    payer_user_id             BIGINT UNSIGNED NOT NULL,
    payment_method            ENUM('DIRECT','STRIPE') NOT NULL DEFAULT 'DIRECT',
    amount                    DECIMAL(10,0)   NOT NULL,
    stripe_fee                DECIMAL(10,0)   NULL,
    platform_fee              DECIMAL(10,0)   NULL,
    platform_fee_rate         DECIMAL(5,4)    NULL,
    net_amount                DECIMAL(10,0)   NULL,
    stripe_payment_intent_id  VARCHAR(100)    NULL,
    status                    ENUM('PENDING','SUCCEEDED','FAILED','REFUNDED') NOT NULL DEFAULT 'PENDING',
    failed_reason             VARCHAR(500)    NULL,
    paid_at                   DATETIME        NULL,
    refunded_at               DATETIME        NULL,
    created_at                DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_fbp_booking (booking_id),
    INDEX idx_fbp_payer (payer_user_id),
    INDEX idx_fbp_status (status),
    CONSTRAINT fk_fbp_booking FOREIGN KEY (booking_id) REFERENCES facility_bookings(id),
    CONSTRAINT fk_fbp_payer FOREIGN KEY (payer_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
