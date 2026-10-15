-- F09.13 通知プリペイドクレジット機能: パッケージマスタテーブル
-- マスタ例外: 全組織共通の固定価格帯。シャーディング時は全シャードにコピーされる参照データのため BIGINT AUTO_INCREMENT を使用。
CREATE TABLE notification_credit_packages (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100)    NOT NULL,
    credits         BIGINT UNSIGNED NOT NULL              COMMENT '付与通数',
    price_jpy       DECIMAL(12,0)   NOT NULL,
    stripe_price_id VARCHAR(200)    NULL                  COMMENT '遅延生成: 初回購入時にStripeへ登録する',
    is_active       TINYINT(1)      NOT NULL DEFAULT 1,
    display_order   INT             NOT NULL DEFAULT 0,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ncp_active (is_active, display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='通知プリペイドクレジットパッケージマスタ';

INSERT INTO notification_credit_packages (name, credits, price_jpy, display_order) VALUES
    ('スタンダード 10万通',  100000,  100000, 10),
    ('スタンダード 20万通',  200000,  180000, 20),
    ('スタンダード 50万通',  500000,  400000, 30),
    ('スタンダード 100万通', 1000000, 700000, 40);
