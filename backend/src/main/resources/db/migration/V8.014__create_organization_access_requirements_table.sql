-- F08.2: 組織全体ロック用支払い要件テーブル
CREATE TABLE organization_access_requirements (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT UNSIGNED NOT NULL,
    payment_item_id  BIGINT UNSIGNED NOT NULL,
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_oar_org_item (organization_id, payment_item_id),
    INDEX idx_oar_payment_item (payment_item_id),
    CONSTRAINT fk_oar_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_oar_payment_item FOREIGN KEY (payment_item_id) REFERENCES payment_items (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
