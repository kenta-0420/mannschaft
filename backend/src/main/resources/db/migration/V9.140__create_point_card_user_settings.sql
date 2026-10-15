-- F18 個人ポイントカードウォレット — ユーザー設定（オプトイン・規約同意・WebAuthn 要求）
-- 設計書: docs/features/F18_point_card_wallet.md §5.5
-- 1:1 設定テーブル: user_id を PK 兼 FK にする（§5.0 例外区分: PK 自然キー）
CREATE TABLE point_card_user_settings (
    user_id                    BIGINT UNSIGNED NOT NULL,
    is_enabled                 TINYINT(1)      NOT NULL DEFAULT 0,
    terms_accepted_at          DATETIME(6)     NULL,
    terms_version              VARCHAR(20)     NULL,
    require_biometric_on_show  TINYINT(1)      NOT NULL DEFAULT 0,
    created_at                 DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                 DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    CONSTRAINT fk_pcus_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
