-- F10.8 アクセス解析: page_view_daily_stats（ページビュー日次集計）
--
-- 日次バッチが前日分を集計して「1 スコープ 1 日 1 行」で upsert する。
-- 手本は analytics_daily_users（V10.069）を「スコープ×日付」に拡張したもの。
--
-- 設計方針（docs/features/F10.8_team_org_access_analytics.md §4.3）:
--   - PK は UUIDv7（BINARY(16)・UuidV7Entity 継承）。CLAUDE.md「DB 設計の原則 #6」に従い、
--     新規テーブル（V146 = major>=70）は AUTO_INCREMENT を使わず UUIDv7 を主キーにする。
--     scope×日で行が増える将来の organization_id シャーディング候補であり、
--     マスタ/シングルトン例外にも該当しない（手本 analytics_daily_* は規約導入前の major<70 で対象外）。
--   - UNIQUE KEY uk_pvds_scope_date (scope_type, scope_id, date) で 1 スコープ 1 日 1 行を保証し、
--     冪等な upsert（INSERT ... ON DUPLICATE KEY UPDATE）と範囲検索（プレフィクスで INDEX 利用）を兼ねる。
--   - クロスドメイン FK は張らない（アプリ層で整合性保証）。

CREATE TABLE page_view_daily_stats (
    id              BINARY(16)      NOT NULL,
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
