-- F22.1 市（Market）謝礼決済 P2-a: 複式記帳台帳（追記専用）
-- 設計書: docs/features/F22.1_market/payment/01_data_model.md §3.3
-- escrow_transactions への FK CASCADE は payment ドメイン内のため許可（CLAUDE.md 原則2）。
CREATE TABLE ledger_entries (
    id                     BINARY(16)       NOT NULL COMMENT 'PK (UUIDv7)',
    escrow_transaction_id  BINARY(16)       NOT NULL COMMENT 'escrow_transactions.id（payment 内 FK・CASCADE）',
    entry_type             VARCHAR(24)      NOT NULL COMMENT 'AUTHORIZE/CAPTURE/TRANSFER_OUT/FEE/REFUND/CANCEL',
    account                VARCHAR(16)      NOT NULL COMMENT '勘定 ESCROW/PAYEE/PLATFORM_FEE/PAYER',
    direction              CHAR(1)          NOT NULL COMMENT 'D（借方）/C（貸方）',
    amount                 INT UNSIGNED     NOT NULL COMMENT '金額（円整数・最小単位）',
    currency               CHAR(3)          NOT NULL DEFAULT 'JPY',
    running_balance        BIGINT           NOT NULL COMMENT '当該取引の累積残高（署名付き・整合検算用）',
    stripe_object_id       VARCHAR(48)      NULL     COMMENT '対応する Stripe オブジェクト（tr_xxx/re_xxx/txn_xxx）',
    created_at             DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '追記時刻（不変）',
    PRIMARY KEY (id),
    CONSTRAINT fk_le_escrow FOREIGN KEY (escrow_transaction_id)
        REFERENCES escrow_transactions (id) ON DELETE CASCADE,
    CONSTRAINT chk_le_direction CHECK (direction IN ('D','C')),
    CONSTRAINT chk_le_entry_type CHECK (entry_type IN
        ('AUTHORIZE','CAPTURE','TRANSFER_OUT','FEE','REFUND','CANCEL')),
    INDEX idx_le_escrow (escrow_transaction_id, created_at),
    INDEX idx_le_stripe_obj (stripe_object_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F22.1 複式記帳台帳（追記専用）';
