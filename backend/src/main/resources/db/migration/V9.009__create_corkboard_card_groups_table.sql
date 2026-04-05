-- F09.8: コルクボードカード⇔セクション中間テーブル
CREATE TABLE corkboard_card_groups (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    card_id     BIGINT UNSIGNED NOT NULL,
    group_id    BIGINT UNSIGNED NOT NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ccg_card_group (card_id, group_id),
    CONSTRAINT fk_ccg_card FOREIGN KEY (card_id) REFERENCES corkboard_cards (id) ON DELETE CASCADE,
    CONSTRAINT fk_ccg_group FOREIGN KEY (group_id) REFERENCES corkboard_groups (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
