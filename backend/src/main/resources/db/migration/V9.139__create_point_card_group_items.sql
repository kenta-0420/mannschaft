-- F18 個人ポイントカードウォレット — グループ ↔ カード 中間テーブル
-- 設計書: docs/features/F18_point_card_wallet.md §5.4
-- 同ドメイン内のため group_id / card_id の双方が ON DELETE CASCADE
CREATE TABLE point_card_group_items (
    id            CHAR(36)     NOT NULL,
    group_id      CHAR(36)     NOT NULL,
    card_id       CHAR(36)     NOT NULL,
    display_order INT UNSIGNED NOT NULL DEFAULT 0,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_pcgi_group_card (group_id, card_id),
    INDEX idx_pcgi_group_order (group_id, display_order),
    INDEX idx_pcgi_card (card_id),
    CONSTRAINT fk_pcgi_group FOREIGN KEY (group_id) REFERENCES point_card_groups(id) ON DELETE CASCADE,
    CONSTRAINT fk_pcgi_card  FOREIGN KEY (card_id)  REFERENCES user_point_cards(id)  ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
