-- F18 個人ポイントカードウォレット — カードグループ
-- 設計書: docs/features/F18_point_card_wallet.md §5.3
-- グループ名・絵文字は暗号化対象外（シーン名であり PII ではない）
CREATE TABLE point_card_groups (
    id            CHAR(36)        NOT NULL,
    user_id       BIGINT UNSIGNED NOT NULL,
    name          VARCHAR(64)     NOT NULL,
    emoji         VARCHAR(8)      NULL,
    display_order INT UNSIGNED    NOT NULL DEFAULT 0,
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_pcg_user (user_id, display_order),
    CONSTRAINT fk_pcg_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
