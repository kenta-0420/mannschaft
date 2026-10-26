-- F18 個人ポイントカードウォレット — ユーザー保有カード
-- 案 B 改修: display_name VARBINARY(1024) NOT NULL（AES-256-GCM 暗号化）/ provider_id NULL 許容 ON DELETE SET NULL
-- 設計書: docs/features/F18_point_card_wallet.md §5.2
CREATE TABLE user_point_cards (
    id              CHAR(36)        NOT NULL,
    user_id         BIGINT UNSIGNED NOT NULL,
    provider_id     CHAR(36)        NULL,
    display_name    VARBINARY(1024) NOT NULL,
    nickname        VARBINARY(1024) NULL,
    barcode_value   VARBINARY(1024) NOT NULL,
    barcode_format  VARCHAR(20)     NOT NULL DEFAULT 'CODE128',
    last4           VARCHAR(4)      NULL,
    memo            VARBINARY(2048) NULL,
    is_favorite     TINYINT(1)      NOT NULL DEFAULT 0,
    display_order   INT UNSIGNED    NOT NULL DEFAULT 0,
    -- Phase 2 用カラム（Phase 1 では常に NULL。後付け ALTER 回避のため最初から用意）
    balance         DECIMAL(12,2)   NULL,
    stamp_count     INT UNSIGNED    NULL,
    last_used_at    DATETIME(6)     NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_upc_user_favorite (user_id, is_favorite, display_order),
    INDEX idx_upc_user_last_used (user_id, last_used_at DESC),
    INDEX idx_upc_provider (provider_id),
    CONSTRAINT fk_upc_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_upc_provider FOREIGN KEY (provider_id) REFERENCES point_card_providers(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
