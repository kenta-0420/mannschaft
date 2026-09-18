ALTER TABLE payment_requests
    ADD COLUMN current_payment_attempt_id BINARY(16) NULL,
    ADD INDEX idx_payment_requests_current_attempt (current_payment_attempt_id);

ALTER TABLE payment_requests DROP CHECK chk_pr_status;
ALTER TABLE payment_requests
    ADD CONSTRAINT chk_pr_status CHECK (status IN ('DRAFT', 'SENT', 'VIEWED', 'PROCESSING', 'PAID', 'OVERDUE', 'CANCELLED'));

CREATE TABLE payment_request_payment_attempts (
    id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    payment_request_id BINARY(16) NOT NULL,
    payer_user_id BIGINT UNSIGNED NOT NULL,
    client_key_hash CHAR(64) NOT NULL,
    stripe_idempotency_key VARCHAR(64) NOT NULL,
    stripe_payment_intent_id VARCHAR(255) NULL,
    escrow_transaction_id BINARY(16) NULL,
    previous_status VARCHAR(12) NOT NULL,
    status VARCHAR(24) NOT NULL,
    completed_at DATETIME NULL,
    failure_code VARCHAR(64) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_pr_attempt_request_key UNIQUE (payment_request_id, client_key_hash),
    CONSTRAINT uk_pr_attempt_stripe_key UNIQUE (stripe_idempotency_key),
    CONSTRAINT uk_pr_attempt_pi UNIQUE (stripe_payment_intent_id),
    CONSTRAINT uk_pr_attempt_escrow UNIQUE (escrow_transaction_id),
    CONSTRAINT chk_pr_attempt_status CHECK (status IN ('CREATING', 'REQUIRES_ACTION', 'SUCCEEDED', 'FAILED')),
    INDEX idx_pr_attempt_request_status (payment_request_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='F08.9 協会請求の決済試行';
