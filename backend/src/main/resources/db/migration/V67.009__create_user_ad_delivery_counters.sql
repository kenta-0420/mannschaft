-- F09.17 Phase 11-a: フリークエンシーキャップ永続層
-- Valkey 上の INCR と日次バッチ (02:00) で同期。保持期間 90 日。
CREATE TABLE user_ad_delivery_counters (
    id                BINARY(16)      NOT NULL,
    user_id           BIGINT UNSIGNED NOT NULL COMMENT 'users.id (FKなし)',
    week_start_date   DATE            NOT NULL COMMENT '週開始日 (ユーザー TZ の月曜 00:00)',
    delivery_count    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '配信件数 (キャップ判定用)',
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_uadc_user_week (user_id, week_start_date),
    INDEX idx_uadc_week (week_start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F09.17 フリークエンシーキャップ週次永続層';
