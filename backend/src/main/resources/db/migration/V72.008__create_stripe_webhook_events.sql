-- F22.1 市（Market）謝礼決済 P2-a: Webhook 冪等性キー
-- 設計書: docs/features/F22.1_market/payment/01_data_model.md §3.5
-- 同一 event_id の二重処理を UNIQUE 制約で物理拒否する（冪等性ゲート）。
CREATE TABLE stripe_webhook_events (
    id              BINARY(16)   NOT NULL COMMENT 'PK (UUIDv7)',
    event_id        VARCHAR(64)  NOT NULL COMMENT 'Stripe イベントID（evt_xxx）。冪等性キー（UNIQUE）',
    type            VARCHAR(64)  NOT NULL COMMENT 'イベント種別（account.updated 等）',
    livemode        BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '本番/テスト区分',
    received_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '受信時刻',
    processed_at    DATETIME     NULL     COMMENT '処理完了時刻（NULL=受信のみ・再試行対象）',
    process_status  VARCHAR(12)  NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED/PROCESSED/IGNORED/FAILED',
    PRIMARY KEY (id),
    CONSTRAINT chk_swe_status CHECK (process_status IN ('RECEIVED','PROCESSED','IGNORED','FAILED')),
    UNIQUE KEY uk_swe_event (event_id),
    INDEX idx_swe_type (type),
    INDEX idx_swe_received (received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F22.1 Webhook 冪等性キー';
