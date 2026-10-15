-- F09.3 Stripe Connect アカウント
CREATE TABLE stripe_connect_accounts (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id                BIGINT UNSIGNED NOT NULL,
    stripe_account_id      VARCHAR(100)    NOT NULL,
    charges_enabled        BOOLEAN         NOT NULL DEFAULT FALSE,
    payouts_enabled        BOOLEAN         NOT NULL DEFAULT FALSE,
    onboarding_completed   BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_sca_user (user_id),
    UNIQUE KEY uq_sca_stripe (stripe_account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
