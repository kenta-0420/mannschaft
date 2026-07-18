-- V158.20260718115027__module_activation_backfill_grandfather.sql
-- モジュール有効化バックフィル戦役 PR-A（BE先行着地・FE結線=PR-Bは後行）。
--
-- 【目的】
--   カタログ #1-70 は登録済だが nav 未結線。既存テナントには enable 行が無いため、
--   後で FE を「enable 行の有無」で nav 表示判定に結線すると、既存テナントが既得機能を
--   一斉に失う回帰が起きる。これを防ぐため、既存テナントへ対象 slug の enable 行を
--   「グランドファザリング（is_grandfathered=1・enabled_by=NULL）」付きで冪等投入する。
--
-- 【グランドファザリング】
--   is_grandfathered=1 の行は「既得機能として通常どおり有効」だが、無料プラン上限
--   （FREE_PLAN_MODULE_LIMIT=10）のカウントからは除外する（ModuleService 側で除外実装）。
--   → 既存テナントが既得機能で無料枠を消費して新規有効化できなくなる事故を根治する。
--   enabled_by=NULL は「システムによる自動付与（人手の操作でない）」を意味する。
--
-- 【冪等性】
--   (3)(4) のバックフィル INSERT は INSERT ... SELECT ... WHERE NOT EXISTS で構成し、
--   何度流しても既存行があれば増えない（uq_team_module / uq_org_module とも整合）。
--
-- 【採番】
--   major=158 = origin/main 全体の最大 major=157（V157 amend_module_descriptions_wave3）の次。
--   minor はタイムスタンプ（date -u '+%Y%m%d%H%M%S'）。

-- ============================================================================
-- (1) is_grandfathered 列追加（両 enable 行テーブル）
--     既存行は DEFAULT 0（=非グランドファザリング）で埋まる。
-- ============================================================================
ALTER TABLE team_enabled_modules
    ADD COLUMN is_grandfathered TINYINT(1) NOT NULL DEFAULT 0 AFTER is_enabled;

ALTER TABLE organization_enabled_modules
    ADD COLUMN is_grandfathered TINYINT(1) NOT NULL DEFAULT 0 AFTER is_enabled;

-- ============================================================================
-- (2) blog_cms 組織レベル availability の是正 0→1
--     組織 blog は実在するのに Wave2（V156）で ORGANIZATION=0 と誤登録した矛盾の根治。
-- ============================================================================
UPDATE module_level_availability mla
    JOIN module_definitions md ON mla.module_id = md.id
    SET mla.is_available = 1, mla.updated_at = NOW()
    WHERE md.slug = 'blog_cms' AND mla.level = 'ORGANIZATION' AND mla.is_available = 0;

-- ============================================================================
-- (3) team バックフィル（budget, workflow）— 冪等 NOT EXISTS
--     列リストは V2.021__create_team_enabled_modules_table.sql の実列に一致：
--       team_id, module_id, is_enabled, is_grandfathered(新), enabled_at, enabled_by,
--       trial_used, created_at, updated_at
--     （disabled_at / trial_expires_at は NULL 許容のため省略。created_at/updated_at は
--       NOT NULL かつ DEFAULT 無しのため必ず値を与える）
-- ============================================================================
INSERT INTO team_enabled_modules
    (team_id, module_id, is_enabled, is_grandfathered, enabled_at, enabled_by, trial_used, created_at, updated_at)
SELECT t.id, m.id, 1, 1, NOW(), NULL, 0, NOW(), NOW()
FROM teams t
JOIN module_definitions m ON m.slug IN ('budget', 'workflow') AND m.deleted_at IS NULL
WHERE t.deleted_at IS NULL
  AND NOT EXISTS (
        SELECT 1 FROM team_enabled_modules e
        WHERE e.team_id = t.id AND e.module_id = m.id);

-- ============================================================================
-- (4) organization バックフィル（tournament, timetable, committee, budget, form, workflow, blog_cms）
--     — 冪等 NOT EXISTS
--     列リストは V2.043__create_organization_enabled_modules_table.sql の実列に一致：
--       organization_id, module_id, is_enabled, is_grandfathered(新), enabled_at, enabled_by,
--       created_at, updated_at
--     （disabled_at は NULL 許容のため省略。created_at/updated_at は DEFAULT CURRENT_TIMESTAMP
--       だが明示 NOW() を与える）
-- ============================================================================
INSERT INTO organization_enabled_modules
    (organization_id, module_id, is_enabled, is_grandfathered, enabled_at, enabled_by, created_at, updated_at)
SELECT o.id, m.id, 1, 1, NOW(), NULL, NOW(), NOW()
FROM organizations o
JOIN module_definitions m
    ON m.slug IN ('tournament', 'timetable', 'committee', 'budget', 'form', 'workflow', 'blog_cms')
   AND m.deleted_at IS NULL
WHERE o.deleted_at IS NULL
  AND NOT EXISTS (
        SELECT 1 FROM organization_enabled_modules e
        WHERE e.organization_id = o.id AND e.module_id = m.id);
