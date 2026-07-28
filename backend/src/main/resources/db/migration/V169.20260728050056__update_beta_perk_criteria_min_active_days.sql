-- =====================================================================
-- F20.3 ベータ特典: beta_perk_criteria の活動日数しきい値（min_active_days）投入
-- =====================================================================
-- 設計書: docs/features/F20.3_beta_perks/01_data_model.md §2 / README.md §2・§7・§9.1
-- 値の根拠: マスター御裁可 2026-07-28 — 活動日数 14 日／評価ウィンドウ 60 日
--           （evaluation_window_days は V162 で既に 60 のため本 migration では変更しない）
--
-- 【対象は INDIVIDUAL のみ・TEAM_ORG は NULL のまま】
--   活動日数（activeDays）は個人（本人のログイン成功日数）の指標であり、
--   BetaPerkEligibilityService（02_api_design.md §2 擬似コード）は
--   INDIVIDUAL のときのみ min_active_days を参照する。TEAM_ORG は
--   min_membership_tenure_days（所属経過日数=30）と min_active_members
--   （アクティブ人数=5）の 2 指標で判定するため、TEAM_ORG 行の
--   min_active_days は今後も NULL のまま運用する。
--
-- 【既に運用で値が入っている行は上書きしない】
--   beta_perk_criteria の閾値は運用値であり、シスアド運用 API
--   （PUT /api/v1/system-admin/beta-perks/criteria/{betaPhase}/{grantKind}）が
--   正準の変更手段である（README §2・02_api_design.md §6）。本 migration は
--   「F10.8 実装前は NULL 運用」としていた初期値に対する一度きりの値投入に
--   すぎず、シスアドが既に運用変更した行を巻き戻してはならない。そのため
--   WHERE 句に min_active_days IS NULL を必須条件として付与する。
--
-- 【本番では自動付与は走らない】
--   min_active_days に実値を入れても、自動付与バッチの起動フラグ
--   mannschaft.beta.auto-grant.enabled の既定値は false であるため、
--   本 migration の適用だけでは 1 件もベータ特典は付与されない。
--   本番での自動付与バッチ有効化には、別途、利用規約第 27 条
--   （ベータテスト特典条項）の弁護士レビューとマスター承認が必要
--   （docs/legal/beta_perk_terms_clause_proposal.md 参照）。
-- =====================================================================
UPDATE beta_perk_criteria
SET min_active_days = 14
WHERE grant_kind = 'INDIVIDUAL'
  AND min_active_days IS NULL;
