-- チームサブスクリプション（プラン状態）テーブル
-- 選択式モジュールの有料プラン判定に使用する。
CREATE TABLE team_subscriptions (
    id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    team_id           BIGINT UNSIGNED NOT NULL,
    plan_type         VARCHAR(30)  NOT NULL DEFAULT 'FREE' COMMENT 'FREE / MODULE / PACKAGE / ORGANIZATION',
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / CANCELLED / EXPIRED / PAST_DUE',
    stripe_subscription_id VARCHAR(200) NULL COMMENT 'Stripe Subscription ID',
    current_period_start   DATE     NULL,
    current_period_end     DATE     NULL,
    cancelled_at      DATETIME     NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_team_subscriptions_team (team_id),
    INDEX idx_team_subscriptions_status (team_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='チームサブスクリプション';
