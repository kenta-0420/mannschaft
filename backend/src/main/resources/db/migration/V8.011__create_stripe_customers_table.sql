-- F08.2: Stripe 顧客 ID 管理テーブル
CREATE TABLE stripe_customers (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id             BIGINT UNSIGNED NOT NULL,
    stripe_customer_id  VARCHAR(100)    NOT NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_sc_user (user_id),
    UNIQUE KEY uq_sc_stripe_customer (stripe_customer_id),
    CONSTRAINT fk_sc_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
