-- F08.2: 支払い項目定義テーブル
CREATE TABLE payment_items (
    id               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    team_id          BIGINT UNSIGNED  NULL,
    organization_id  BIGINT UNSIGNED  NULL,
    name             VARCHAR(100)     NOT NULL,
    description      VARCHAR(500)     NULL,
    type             ENUM('ANNUAL_FEE', 'MONTHLY_FEE', 'ITEM', 'DONATION') NOT NULL,
    amount           DECIMAL(10,2)    NOT NULL,
    currency         CHAR(3)          NOT NULL DEFAULT 'JPY',
    stripe_product_id VARCHAR(100)    NULL,
    stripe_price_id  VARCHAR(100)     NULL,
    is_active        BOOLEAN          NOT NULL DEFAULT TRUE,
    display_order    SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    grace_period_days SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    created_by       BIGINT UNSIGNED  NULL,
    created_at       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at       DATETIME         NULL,
    PRIMARY KEY (id),
    INDEX idx_pi_team_id (team_id),
    INDEX idx_pi_organization_id (organization_id),
    INDEX idx_pi_stripe_price (stripe_price_id),
    CONSTRAINT fk_pi_team        FOREIGN KEY (team_id)         REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_pi_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_pi_created_by  FOREIGN KEY (created_by)      REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT chk_pi_scope CHECK (
        (team_id IS NOT NULL AND organization_id IS NULL)
        OR (team_id IS NULL AND organization_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
