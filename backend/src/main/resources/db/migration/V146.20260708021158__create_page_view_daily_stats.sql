-- F10.8 アクセス解析: page_view_daily_stats（ページビュー日次集計）
--
-- 日次バッチが前日分を集計して「1 スコープ 1 日 1 行」で upsert する。
-- 手本は analytics_daily_users（V10.069）を「スコープ×日付」に拡張したもの。
--
-- 設計方針（docs/features/F10.8_team_org_access_analytics.md §4.3）:
--   - PK は BIGINT UNSIGNED AUTO_INCREMENT（BaseEntity / IDENTITY）。
--   - UNIQUE KEY uk_pvds_scope_date (scope_type, scope_id, date) で 1 スコープ 1 日 1 行を保証し、
--     冪等な upsert（INSERT ... ON DUPLICATE KEY UPDATE）と範囲検索（プレフィクスで INDEX 利用）を兼ねる。
--   - クロスドメイン FK は張らない（アプリ層で整合性保証）。

CREATE TABLE page_view_daily_stats (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type      VARCHAR(20)     NOT NULL,
    scope_id        BIGINT UNSIGNED NOT NULL,
    date            DATE            NOT NULL,
    total_views     INT UNSIGNED    NOT NULL DEFAULT 0,
    unique_visitors INT UNSIGNED    NOT NULL DEFAULT 0,
    member_views    INT UNSIGNED    NOT NULL DEFAULT 0,
    guest_views     INT UNSIGNED    NOT NULL DEFAULT 0,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pvds_scope_date (scope_type, scope_id, date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
