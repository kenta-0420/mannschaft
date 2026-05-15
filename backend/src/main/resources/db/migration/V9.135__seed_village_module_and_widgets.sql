-- F17.1 Phase 1: village モジュール定義シード
--
-- module_definitions は (name, slug, description, module_type, module_number, ...) 構造。
-- 既存最大 module_number = 48（V13.053 repair_longterm_plan）。本機能は 49 を確保。
--
-- 注: dashboard_widgets テーブルは現状の DB スキーマには存在しない。
--     ウィジェット定義（VILLAGE_FEED / VILLAGE_LOBBY_DIGEST / VILLAGE_PINNED_LIST）は
--     dashboard_widgets テーブル新設の足軽（後続単位）が同テーブル新設時に併せてシード投入する。

INSERT INTO module_definitions (
    name, slug, description, module_type, module_number,
    requires_paid_plan, trial_days, is_active, created_at, updated_at
) VALUES (
    '村', 'village', '組織・チームの垣根を越える横断コミュニティ（F17.1）', 'OPTIONAL', 49,
    0, 14, 1, NOW(), NOW()
);
