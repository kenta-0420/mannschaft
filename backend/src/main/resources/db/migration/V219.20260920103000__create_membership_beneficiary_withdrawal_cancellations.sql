CREATE TABLE membership_beneficiary_withdrawal_cancellations (
    id BINARY(16) NOT NULL,
    subscription_id BINARY(16) NOT NULL,
    beneficiary_user_id BIGINT UNSIGNED NOT NULL,
    withdrawal_attempt_id BINARY(16) NOT NULL,
    stripe_subscription_id VARCHAR(255) NULL,
    status VARCHAR(16) NOT NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbwc_subscription (subscription_id),
    KEY idx_mbwc_retry (status, updated_at),
    CONSTRAINT chk_mbwc_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
