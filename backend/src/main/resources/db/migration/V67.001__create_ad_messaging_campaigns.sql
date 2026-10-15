-- F09.17 Phase 11-a: 広告主ターゲット配信キャンペーン本体
-- TenantAware: organization_id を保持し AbstractTenantAwareRepository を使用
-- クロスドメイン FK 禁止: advertiser_account_id / organization_id / created_by_user_id は FK なし INDEX のみ
CREATE TABLE ad_messaging_campaigns (
    id                       BINARY(16)       NOT NULL,
    advertiser_account_id    BIGINT UNSIGNED  NOT NULL COMMENT 'F09.11 advertiser_accounts.id (FKなし)',
    organization_id          BIGINT UNSIGNED  NOT NULL COMMENT 'テナント分離キー (FKなし)',
    name                     VARCHAR(120)     NOT NULL COMMENT 'キャンペーン名 (広告主表示用)',
    status                   ENUM('DRAFT','REVIEW','APPROVED','SCHEDULED','DELIVERING','PAUSED','COMPLETED','BLOCKED','CANCELLED')
                                              NOT NULL DEFAULT 'DRAFT' COMMENT 'キャンペーン状態',
    total_budget_yen         BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '総予算 (円)',
    consumed_budget_yen      BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '消費済予算 (円・月次バッチで更新)',
    frequency_cap_override   TINYINT UNSIGNED NULL     COMMENT 'キャンペーン個別キャップ (NULL=デフォルト週3件)',
    starts_at                DATETIME         NOT NULL COMMENT '配信開始時刻',
    ends_at                  DATETIME         NOT NULL COMMENT '配信終了時刻',
    scheduled_timezone       VARCHAR(50)      NOT NULL DEFAULT 'Asia/Tokyo' COMMENT '配信スケジュール基準 TZ',
    moderation_status        ENUM('PENDING','AUTO_PASSED','AUTO_FLAGGED','APPROVED','BLOCKED')
                                              NOT NULL DEFAULT 'PENDING' COMMENT '審査状態',
    blocked_reason           TEXT             NULL     COMMENT 'BLOCKED 時の理由',
    created_by_user_id       BIGINT UNSIGNED  NOT NULL COMMENT '作成者 user_id (FKなし)',
    created_at               DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at               DATETIME         NULL     COMMENT '論理削除 (DRAFT のみ許可)',
    PRIMARY KEY (id),
    INDEX idx_amc_org_status (organization_id, status, deleted_at),
    INDEX idx_amc_advertiser (advertiser_account_id, deleted_at),
    INDEX idx_amc_status_window (status, starts_at, ends_at),
    INDEX idx_amc_moderation (moderation_status, created_at),
    INDEX idx_amc_created_by (created_by_user_id),
    CONSTRAINT chk_amc_window CHECK (starts_at < ends_at),
    CONSTRAINT chk_amc_freq CHECK (frequency_cap_override IS NULL OR frequency_cap_override BETWEEN 1 AND 30)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F09.17 メッセージ型キャンペーン本体';
