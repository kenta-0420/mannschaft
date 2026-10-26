-- F22.1 市（Market）謝礼決済 P2-a: 返金記録（部分/全額）
-- 設計書: docs/features/F22.1_market/payment/01_data_model.md §3.4
-- escrow_transactions への FK CASCADE は payment ドメイン内のため許可（CLAUDE.md 原則2）。
CREATE TABLE refunds (
    id                     BINARY(16)       NOT NULL COMMENT 'PK (UUIDv7)',
    escrow_transaction_id  BINARY(16)       NOT NULL COMMENT 'escrow_transactions.id（payment 内 FK・CASCADE）',
    stripe_refund_id       VARCHAR(32)      NOT NULL COMMENT 're_xxx（一意）',
    amount                 INT UNSIGNED     NOT NULL COMMENT '返金額（円整数・最小単位）',
    currency               CHAR(3)          NOT NULL DEFAULT 'JPY',
    reason                 VARCHAR(32)      NOT NULL
                               COMMENT 'requested_by_customer/duplicate/dispute_resolution/cancellation 等',
    reason_detail          VARCHAR(500)     NULL     COMMENT '運営・札主の補足（PII 非含意）',
    refunded_by_user_id    BIGINT UNSIGNED  NULL     COMMENT '返金操作者（論理参照・監査）',
    status                 VARCHAR(12)      NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCEEDED/FAILED',
    created_at             DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_rf_escrow FOREIGN KEY (escrow_transaction_id)
        REFERENCES escrow_transactions (id) ON DELETE CASCADE,
    CONSTRAINT chk_rf_status CHECK (status IN ('PENDING','SUCCEEDED','FAILED')),
    UNIQUE KEY uk_rf_stripe (stripe_refund_id),
    INDEX idx_rf_escrow (escrow_transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F22.1 返金記録（部分/全額）';
