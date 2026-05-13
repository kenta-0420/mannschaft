-- F09.13 通知プリペイドクレジット機能: 月次使用量集計テーブル
-- (organization_id, month, source_type) の3カラムで UNIQUE。
CREATE TABLE notification_monthly_usage (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT UNSIGNED NOT NULL,
    month           DATE            NOT NULL              COMMENT '集計月（YYYY-MM-01）',
    source_type     ENUM('NOTIFY_ALL','DIRECT_MAIL','CONFIRMABLE') NOT NULL COMMENT '通知発生源',
    used_count      BIGINT          NOT NULL DEFAULT 0    COMMENT '合計使用通数',
    free_count      BIGINT          NOT NULL DEFAULT 0    COMMENT '無料枠から消費した通数',
    credit_count    BIGINT          NOT NULL DEFAULT 0    COMMENT 'クレジット残高から消費した通数',
    grace_count     BIGINT          NOT NULL DEFAULT 0    COMMENT '猶予期間中の送信通数（翌月相殺予定）',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_nmu (organization_id, month, source_type),
    CONSTRAINT fk_nmu_org FOREIGN KEY (organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='通知月次使用量集計';
