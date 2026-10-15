-- ストレージプラン定義テーブルを作成
-- SYSTEM_ADMIN が管理するストレージプラン定義。スコープレベル別（ORGANIZATION/TEAM/PERSONAL）に
-- 容量・月額料金・超過課金単価を定義する
-- 設計書: docs/cross-cutting/storage_quota.md 参照
--
-- storage_subscriptions テーブルが storage_plans を参照するため、先に作成する必要がある
CREATE TABLE storage_plans (
    id                  BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT,
    name                VARCHAR(100)      NOT NULL COMMENT 'プラン名（例: フリー, スタンダード, プレミアム）',
    scope_level         VARCHAR(20)       NOT NULL COMMENT '適用スコープレベル: ORGANIZATION / TEAM / PERSONAL',
    included_bytes      BIGINT UNSIGNED   NOT NULL COMMENT '無料枠のバイト数（例: 5GB = 5,368,709,120）',
    max_bytes           BIGINT UNSIGNED   NULL     COMMENT 'ハード上限バイト数（NULL = 超過課金で無制限）',
    price_monthly       DECIMAL(10,2)     NOT NULL DEFAULT 0 COMMENT '月額料金（税抜。0 = 無料プラン）',
    price_yearly        DECIMAL(10,2)     NULL     COMMENT '年額料金（NULL = 月額のみ）',
    price_per_extra_gb  DECIMAL(10,2)     NULL     COMMENT '超過時の GB 単価（NULL = 超過不可・ハードブロック）',
    is_default          BOOLEAN           NOT NULL DEFAULT FALSE COMMENT 'TRUE = 新規作成時に自動適用されるデフォルトプラン（scope_level 別に1つ）',
    sort_order          SMALLINT          NOT NULL DEFAULT 0 COMMENT 'プラン選択画面の表示順',
    created_at          DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at          DATETIME          NULL     COMMENT '論理削除（SoftDeletableEntity 適用）',
    PRIMARY KEY (id),
    INDEX idx_sp_level (scope_level, is_default, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='ストレージプラン定義テーブル（SYSTEM_ADMIN 管理）';

-- デフォルト無料プランの初期データ
-- 暫定値: 組織 50GB / チーム 5GB / 個人 1GB
INSERT INTO storage_plans (name, scope_level, included_bytes, max_bytes, price_monthly, price_yearly, price_per_extra_gb, is_default, sort_order)
VALUES
    ('フリー（組織）', 'ORGANIZATION', 53687091200,  53687091200,  0.00, NULL, NULL, TRUE,  1),  -- 50GB
    ('フリー（チーム）', 'TEAM',         5368709120,   5368709120,   0.00, NULL, NULL, TRUE,  1),  -- 5GB
    ('フリー（個人）',  'PERSONAL',     1073741824,   1073741824,   0.00, NULL, NULL, TRUE,  1);   -- 1GB
