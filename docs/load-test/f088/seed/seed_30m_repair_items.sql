-- ============================================================
-- F08.8 Phase 6 負荷試験用シードSQL
-- 30万件の repair_plan_items を生成する
--
-- 前提条件:
--   - organization_id=1 の組織が存在すること
--   - scope_id=1 の TEAM が organization_id=1 に属すること
--   - created_by=1 のユーザーが存在すること（ADMIN ロール推奨）
--   - repair_longterm_plan モジュールが有効であること
--
-- 実行目安: 数分（MySQL 8.0 環境依存）
-- 削除方法: 本ファイル末尾の DELETE 文を参照
-- ============================================================

-- MySQL 8.0 での再帰 CTE を使った高速バルク INSERT
-- 1,000 件単位で分割していないが、一括INSERT で十分な速度が出る。
-- メモリ不足が発生する場合は LIMIT 300000 を 50000 ずつ 6 回に分割すること。

SET SESSION group_concat_max_len = 1000000;
SET SESSION net_read_timeout = 300;
SET SESSION net_write_timeout = 300;
SET SESSION wait_timeout = 300;
SET FOREIGN_KEY_CHECKS = 0;

-- 一時的にカウンタテーブルを使って連番を生成する
-- （information_schema.columns のクロス結合で 300,000 行を生成）

INSERT INTO repair_plan_items (
    id,
    organization_id,
    scope_type,
    scope_id,
    template_id,
    category,
    title,
    description,
    planned_year,
    planned_month,
    estimated_amount,
    cpi_inflation_basis_year,
    status,
    linked_work_package_id,
    tags,
    created_by,
    updated_by,
    version,
    created_at,
    updated_at,
    deleted_at
)
SELECT
    UUID_TO_BIN(UUID()),                              -- id (UUIDv4 で代用: 負荷試験用なので UUIDv7 不要)
    1,                                                -- organization_id
    'TEAM',                                           -- scope_type
    1,                                                -- scope_id
    NULL,                                             -- template_id (負荷試験用のため null)
    ELT(
        1 + (seq MOD 6),
        '外壁',
        '屋根',
        '給排水',
        '電気',
        'エレベーター',
        '共用廊下'
    ),                                                -- category (6カテゴリを循環)
    CONCAT(
        ELT(1 + (seq MOD 6), '外壁', '屋根', '給排水', '電気', 'エレベーター', '共用廊下'),
        '修繕工事 ',
        (2010 + (seq MOD 40)),
        '年度 No.',
        (seq + 1)
    ),                                                -- title
    NULL,                                             -- description
    2010 + (seq MOD 40),                              -- planned_year (2010〜2049 を循環)
    1 + (seq MOD 12),                                 -- planned_month (1〜12 を循環)
    FLOOR(RAND() * 9000000) + 1000000,                -- estimated_amount (100万〜1,000万円)
    2025,                                             -- cpi_inflation_basis_year
    ELT(
        1 + (seq MOD 5),
        'PLANNED',
        'PLANNED',
        'PLANNED',
        'IN_PROGRESS',
        'COMPLETED'
    ),                                                -- status (PLANNED 多め)
    NULL,                                             -- linked_work_package_id
    NULL,                                             -- tags
    1,                                                -- created_by (ADMIN ユーザー ID=1)
    NULL,                                             -- updated_by
    0,                                                -- version
    NOW() - INTERVAL (seq MOD 1825) DAY,              -- created_at (最大5年前まで分散)
    NOW() - INTERVAL (seq MOD 1825) DAY,              -- updated_at
    NULL                                              -- deleted_at
FROM (
    -- 300,000 行の連番を生成する（MySQL 8.0 再帰 CTE）
    WITH RECURSIVE counter(seq) AS (
        SELECT 0
        UNION ALL
        SELECT seq + 1 FROM counter WHERE seq < 299999
    )
    SELECT seq FROM counter
    LIMIT 300000
) AS nums;

SET FOREIGN_KEY_CHECKS = 1;

-- 適用確認クエリ（実行後に件数を確認すること）
-- SELECT COUNT(*) AS inserted_count
-- FROM repair_plan_items
-- WHERE organization_id = 1
--   AND scope_type = 'TEAM'
--   AND scope_id = 1;
-- 期待値: 300000（既存データとの合計）

-- ============================================================
-- 【後片付け】試験後のクリーンアップ
-- created_by=1 かつ title に '負荷試験' または '修繕工事 20' で始まる行を削除する場合:
--
-- DELETE FROM repair_plan_items
-- WHERE organization_id = 1
--   AND scope_type = 'TEAM'
--   AND scope_id = 1
--   AND created_by = 1
--   AND title REGEXP '^(外壁|屋根|給排水|電気|エレベーター|共用廊下)修繕工事 [0-9]{4}年度 No\\.';
--
-- 注意: 上記 DELETE は title の命名パターンで判別するため、
-- 本番データに同パターンのタイトルがある場合は別途条件を追加すること。
-- ============================================================
