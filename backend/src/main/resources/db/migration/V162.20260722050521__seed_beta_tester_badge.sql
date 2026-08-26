-- =====================================================================
-- F20.3 ベータ特典: ベータテスター称号バッジのシード（F04.7 badges 流用）
-- =====================================================================
-- 設計書: docs/features/F20.3_beta_perks/01_data_model.md §5
-- badge_type に enum 新値は足さない（BadgeType 実値は STANDARD/MILESTONE/SPECIAL のみ）。
-- ベータ称号は badge_type='SPECIAL' の badges 行として識別する。
-- 授与は user_badges INSERT（awarded_by='SYSTEM'・period_label='BETA_PHASE_n'）で行う（本 PR では未実装）。
--
-- 冪等: badges には UNIQUE 制約が無い（INDEX idx_b_scope_active のみ）ため、from-scratch 番人テストで
--       二重挿入しないよう INSERT ... WHERE NOT EXISTS で冪等化する。
-- scope_type='PLATFORM'・scope_id=0 は sentinel（プラットフォーム横断 badge の前例なし・設計書 B-5）。
-- =====================================================================
INSERT INTO badges
    (scope_type, scope_id, name, badge_type, condition_type,
     condition_value, condition_period, icon_emoji, icon_key,
     is_system, is_repeatable, is_active, version)
SELECT
    'PLATFORM', 0, 'ベータテスター', 'SPECIAL', 'MANUAL',
    NULL, NULL, '🚀', NULL,
    1, 0, 1, 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM badges
     WHERE scope_type = 'PLATFORM' AND scope_id = 0 AND name = 'ベータテスター'
);
