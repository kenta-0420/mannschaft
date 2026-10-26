-- F09.13 通知プリペイドクレジット機能: 購入履歴テーブル
-- テナントごとに行が増えるため UUIDv7 原則の対象だが、既存 Stripe 基盤（BIGINT ID）との
-- 整合性を保つため BIGINT AUTO_INCREMENT を採用する（既存パターンに準拠）。
-- organization_id でシャーディングを行う際は fk_ncp_org を削除してアプリ層で整合性保証する。
CREATE TABLE notification_credit_purchases (
    id                         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    organization_id            BIGINT UNSIGNED NOT NULL,
    package_id                 BIGINT UNSIGNED NOT NULL,
    purchased_by_user_id       BIGINT UNSIGNED NOT NULL,
    credits_granted            BIGINT          NOT NULL COMMENT '購入時の付与通数スナップショット',
    remaining_credits          BIGINT          NOT NULL COMMENT 'FIFO消費追跡用残クレジット',
    price_jpy                  DECIMAL(12,0)   NOT NULL COMMENT '購入時の価格スナップショット',
    stripe_checkout_session_id VARCHAR(200)    NULL,
    stripe_payment_intent_id   VARCHAR(200)    NULL,
    payment_status             ENUM('PENDING','PAID','CANCELLED','REFUNDED') NOT NULL DEFAULT 'PENDING',
    paid_at                    DATETIME        NULL,
    receipt_url                VARCHAR(500)    NULL,
    expires_at                 DATETIME        NULL    COMMENT 'paid_at + 2年',
    alert_sent_30d             TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '有効期限30日前アラート送信済みフラグ',
    alert_sent_7d              TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '有効期限7日前アラート送信済みフラグ',
    expired_at                 DATETIME        NULL    COMMENT '失効処理実施日時',
    idempotency_key            VARCHAR(100)    NULL    COMMENT 'Webhook冪等キー（UUID）',
    created_at                 DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ncp_org        (organization_id, payment_status),
    INDEX idx_ncp_expires    (expires_at, payment_status, expired_at),
    INDEX idx_ncp_session    (stripe_checkout_session_id),
    UNIQUE KEY uq_ncp_idem   (idempotency_key),
    CONSTRAINT fk_ncp_pkg    FOREIGN KEY (package_id) REFERENCES notification_credit_packages(id),
    CONSTRAINT fk_ncp_org    FOREIGN KEY (organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='通知プリペイドクレジット購入履歴';
