-- 祝日マスタテーブル
-- 組織・チーム単位またはシステム共通で祝日を管理する。
-- スケジュール表示・施設料金計算等で参照される。
CREATE TABLE holiday_master (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    scope_type  VARCHAR(20)  NOT NULL DEFAULT 'SYSTEM' COMMENT 'SYSTEM / ORGANIZATION / TEAM',
    scope_id    BIGINT       NOT NULL DEFAULT 0        COMMENT 'scope_type=SYSTEM の場合は 0',
    holiday_date DATE        NOT NULL,
    name        VARCHAR(200) NOT NULL                  COMMENT '祝日名',
    country     VARCHAR(5)   NOT NULL DEFAULT 'JP'     COMMENT 'ISO 3166-1 alpha-2',
    is_recurring TINYINT(1)  NOT NULL DEFAULT 0        COMMENT '毎年繰り返すか（振替休日等は FALSE）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_holiday_master_date (holiday_date),
    INDEX idx_holiday_master_scope (scope_type, scope_id, holiday_date),
    UNIQUE KEY uk_holiday_master (scope_type, scope_id, holiday_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='祝日マスタ';
