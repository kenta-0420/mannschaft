-- F09.13 通知プリペイドクレジット機能: 組織別残高テーブル
-- 組織ごとに 1 行のみ存在する（UNIQUE KEY）。
-- @Version楽観的ロックと PESSIMISTIC_WRITE で並行制御する。
CREATE TABLE organization_notification_balances (
    id                    BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    organization_id       BIGINT UNSIGNED NOT NULL UNIQUE,
    free_used_this_month  BIGINT          NOT NULL DEFAULT 0 COMMENT '今月の無料枠使用通数',
    free_quota_month      DATE            NOT NULL            COMMENT '無料枠リセット月（YYYY-MM-01）',
    alert_sent_this_month TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '月間9000通アラート送信済みフラグ',
    credit_balance        BIGINT          NOT NULL DEFAULT 0  COMMENT 'クレジット残高（マイナスあり：負債）',
    grace_period_start_at DATETIME        NULL                COMMENT '残高不足による猶予期間開始日時',
    grace_period_debt     BIGINT          NOT NULL DEFAULT 0  COMMENT '猶予期間中の累積負債（翌月1日に相殺）',
    version               BIGINT          NOT NULL DEFAULT 0  COMMENT 'JPA楽観的ロック用バージョン',
    created_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_onb_org FOREIGN KEY (organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='組織別通知クレジット残高';
