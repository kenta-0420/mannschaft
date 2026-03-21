-- F08.2: チーム全体ロック用支払い要件テーブル
CREATE TABLE team_access_requirements (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id          BIGINT UNSIGNED NOT NULL,
    payment_item_id  BIGINT UNSIGNED NOT NULL,
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_tar_team_item (team_id, payment_item_id),
    INDEX idx_tar_payment_item (payment_item_id),
    CONSTRAINT fk_tar_team         FOREIGN KEY (team_id)         REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_tar_payment_item FOREIGN KEY (payment_item_id) REFERENCES payment_items (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
