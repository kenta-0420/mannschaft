-- F08.2: コンテンツ単位アクセスゲート設定テーブル
CREATE TABLE content_payment_gates (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    payment_item_id  BIGINT UNSIGNED NOT NULL,
    content_type     VARCHAR(50)     NOT NULL,
    content_id       BIGINT UNSIGNED NOT NULL,
    is_title_hidden  BOOLEAN         NOT NULL DEFAULT FALSE,
    created_by       BIGINT UNSIGNED NULL,
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_cpg_item_content (payment_item_id, content_type, content_id),
    INDEX idx_cpg_content (content_type, content_id),
    CONSTRAINT fk_cpg_payment_item FOREIGN KEY (payment_item_id) REFERENCES payment_items (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cpg_created_by   FOREIGN KEY (created_by)      REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
