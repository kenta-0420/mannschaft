-- surveys.scope_type 正準化 (M0)
-- 汚染値（URLパス語 "teams"/"organizations"）を正準 enum 値（"TEAM"/"ORGANIZATION"）に一括修正し、
-- その後 ENUM 型に変換して不正値の混入を DB レベルで防ぐ。
--
-- 背景: SurveyController が URLパスの {scopeType}（"teams"/"organizations"）を
--       そのまま INSERT していたため、実DB に汚染値が混在していた。
--       AccessControlService.ScopeType.valueOf(scopeType) は正準値のみを受け付けるため、
--       汚染値が残存していると読取時に IllegalArgumentException が発生する。
--
-- 実行前の分布 (2026-06-18 確認):
--   teams=56, organizations=19, TEAM=3, ORGANIZATION=0
-- 想定外値 (NULL/空/その他) は 0 件確認済み。
--
-- Step 1: URLパス語 "teams" を正準値 "TEAM" に更新
UPDATE surveys SET scope_type = 'TEAM' WHERE BINARY scope_type = 'teams';

-- Step 2: URLパス語 "organizations" を正準値 "ORGANIZATION" に更新
UPDATE surveys SET scope_type = 'ORGANIZATION' WHERE BINARY scope_type = 'organizations';

-- Step 3: 列型を ENUM に変更（想定外値ゼロ確定後・NOT NULL 維持）
-- 元の型: varchar(20) NOT NULL（DEFAULT なし）
ALTER TABLE surveys
    MODIFY COLUMN scope_type ENUM('ORGANIZATION', 'TEAM') NOT NULL
        COMMENT '正準スコープ種別: ORGANIZATION / TEAM';
