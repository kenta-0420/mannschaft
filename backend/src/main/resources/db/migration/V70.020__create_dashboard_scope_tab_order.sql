-- =============================================================
-- F22.1: 個人/チーム/組織 横スワイプ・ダッシュボード
--   ダッシュボードのチーム/組織タグ表示順（ユーザー個人設定）
--
-- 設計書: docs/features/F22.1_swipe_scope_dashboard/01_db_design.md §2.1 / §5
--
-- 設計原則への準拠:
--   - 原則1: クロスドメインFKを作らない（user_id / scope_id に FK 制約なし）
--   - 原則6: 新規テーブルの主キーは UUIDv7（BINARY(16)）
--   - 原則7: organization_id を持たない user_id 単位の個人設定のため
--            AbstractTenantAwareRepository は不適用（01 §3 判断記録）
-- =============================================================

CREATE TABLE dashboard_scope_tab_order (
    id          BINARY(16)  NOT NULL COMMENT 'UUIDv7 主キー（原則6）',
    user_id     BIGINT      NOT NULL COMMENT 'users.id（FK制約なし。クロスドメインFK禁止原則）',
    scope_type  VARCHAR(20) NOT NULL COMMENT 'タグ種別（TEAM / ORGANIZATION）',
    scope_id    BIGINT      NOT NULL COMMENT 'チームID または 組織ID（FK制約なし）',
    sort_order  INT         NOT NULL DEFAULT 0 COMMENT '表示順（昇順。小さいほど先頭）',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_dsto_user_scope (user_id, scope_type, scope_id),
    INDEX idx_dsto_user_scope_sort (user_id, scope_type, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='ダッシュボード横スワイプ：チーム/組織タグの表示順（ユーザー個人設定）';
