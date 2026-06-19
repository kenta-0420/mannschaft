-- Phase 5-B（最終局面・第二弾）: クロスドメインFK撤廃 — 「RESTRICT → 他ドメイン実テーブル」11件を撤廃only（孤児保持）。
--
-- 1000万ユーザー耐久DB再構築 クロスドメインFK撤廃キャンペーン Phase 5-B。
-- CLAUDE.md §1「クロスドメインFKは作らない」/ §2「CASCADE/SET NULL のクロスドメイン削除連鎖は禁止」
-- 原則に従い、クロスドメイン（別ドメインの実テーブル）を参照する FK を撤廃する。
-- 第一〜四陣で「user/team/org 親の CASCADE」「群1=users 親 SET NULL」「群2=他テーブル SET NULL」を全廃し、
-- 最終局面 5-A（V114.001）で「発火不能群」12件（CASCADE 7＋RESTRICT→org/team 5）を撤廃済。
-- 本陣（最終局面 5-B）が対象とするのは「残った RESTRICT」のうち、参照先が org/team 以外の
-- 他ドメイン実テーブル（venues / module_definitions / electronic_seals / shared_files / schedules /
-- budget_categories / budget_fiscal_years / projects / shift_schedules / shift_slots）を参照する RESTRICT 11件である。
--
-- ━━━ なぜ「撤廃only・孤児保持・リスナー/データ操作/NULL化なし」が無条件に安全なのか ━━━
-- 本 PR-5b の11件は、参照先テーブルがいずれも「論理削除（deleted_at）のみで物理削除されない」
-- または「書き込みが運用バッチのみのマスタで物理削除されない」:
--   ・venues             : 全テナント共通のマスタ（会場辞書）。usage_count を増やすのみで物理 DELETE 経路なし（deleted_at 列も無く論理削除もしない＝不変マスタ）。
--   ・module_definitions : モジュール定義マスタ。deleted_at による論理削除のみ（物理 DELETE 経路なし）。
--   ・electronic_seals   : deleted_at による論理削除のみ。
--   ・shared_files       : deleted_at による論理削除のみ。
--   ・schedules          : deleted_at による論理削除のみ（コアに準ずる予定マスタ）。
--   ・budget_categories  : deleted_at による論理削除のみ。
--   ・budget_fiscal_years: deleted_at による論理削除のみ。
--   ・projects           : deleted_at による論理削除のみ。
--   ・shift_schedules    : deleted_at による論理削除のみ。
--   ・shift_slots        : 親 shift_schedules の論理削除に追従して扱われ、本番では shift_schedules 経由でしか
--                          消えない（deleteByScheduleId は親 schedule の論理削除フローに紐づく）。RESTRICT 発火契機なし。
-- 参照先の行が物理削除される契機が存在しない以上、既定の ON DELETE RESTRICT は現実には発火し得ない
-- ＝挙動を一切変えずに FK だけを撤廃できる（5-A の発火不能群撤廃と同じ論理）。
-- 撤廃後に万一参照先が物理削除されても、子の外部キー列は孤児値を保持する（RESTRICT 阻止が起きない）。
-- 参照整合性は今後アプリ層で保証する（CLAUDE.md §1）。
--
-- 本 migration は「純粋な FK 撤廃のみ」である。リスナー・データ操作・NULL 化処理は一切伴わない。
-- 番人テスト（FlywayExistingDataRestrictOtherTableCrossDomainFkMigrationTest）が守る不変条件は
-- 「参照先テーブルの行を（テスト内で意図的に）物理 DELETE しても、参照元の外部キー列が削除/NULL化されず
--  孤児値を保持し続ける」ことであり、RESTRICT 撤廃only の肝を直接検証する。
--
-- ━━━ 対象一覧（11件・すべて明示名・すべて既定 RESTRICT）━━━
--  1. activity_results           / fk_ar_venue                      (venue_id           → venues)
--  2. schedules                  / fk_sch_venue                     (venue_id           → venues)
--  3. analytics_daily_modules    / fk_analytics_daily_modules_module(module_id           → module_definitions)
--  4. chart_intake_forms         / fk_cif_seal                      (electronic_seal_id  → electronic_seals)
--  5. disclosure_exports         / fk_de_shared_file                (shared_file_id      → shared_files)
--  6. events                     / fk_events_schedule               (schedule_id         → schedules)
--  7. shift_budget_allocations   / fk_sba_budget_category           (budget_category_id  → budget_categories)
--  8. shift_budget_allocations   / fk_sba_fiscal_year               (fiscal_year_id      → budget_fiscal_years)
--  9. shift_budget_allocations   / fk_sba_project                   (project_id          → projects)
-- 10. shift_budget_consumptions  / fk_sbc_shift                     (shift_id            → shift_schedules)
-- 11. shift_budget_consumptions  / fk_sbc_slot                      (slot_id             → shift_slots)
--
-- ━━━ index 判定（FK 撤廃で暗黙バッキング index が消えても CREATE INDEX 追加が必要か）━━━
-- 結論: 11件とも CREATE INDEX 追加は不要。各撤廃対象 FK 列を「先頭」に持つ非FK index（または UNIQUE）が既存:
--  1. activity_results.venue_id            : INDEX idx_ar_venue (venue_id) 既存（先頭一致）→ 追加不要。
--  2. schedules.venue_id                   : INDEX idx_sch_venue (venue_id) 既存（先頭一致）→ 追加不要。
--  3. analytics_daily_modules.module_id    : UNIQUE uk_date_module (date, module_id) は module_id が先頭でない。
--       しかし module_id は集計バッチが「module ごと」に逆引きしない冷たい列（クエリは常に date 主導）であり、
--       module_definitions マスタへの前向き参照のみ → index 追加不要。
--  4. chart_intake_forms.electronic_seal_id: 電子印鑑への前向き参照（write-only / 逆引き finder なし・冷たい関連列）→ index 追加不要。
--  5. disclosure_exports.shared_file_id     : 生成 PDF の shared_file への前向き参照（逆引き finder なし・冷たい関連列）→ index 追加不要。
--  6. events.schedule_id                    : UNIQUE uq_events_schedule (schedule_id) 既存（先頭一致）→ 追加不要。
--  7. shift_budget_allocations.budget_category_id : UNIQUE uq_sba_scope_category_period（先頭=organization_id だが、
--       budget_category_id は予算配分の絞り込みで先頭に来ない冷たい列。予算費目マスタへの前向き参照）→ index 追加不要。
--  8. shift_budget_allocations.fiscal_year_id     : INDEX idx_sba_fiscal (fiscal_year_id) 既存（先頭一致）→ 追加不要。
--  9. shift_budget_allocations.project_id          : INDEX idx_sba_project (project_id) 既存（先頭一致）→ 追加不要。
-- 10. shift_budget_consumptions.shift_id           : INDEX idx_sbc_shift (shift_id, status) 既存（先頭一致）→ 追加不要。
-- 11. shift_budget_consumptions.slot_id            : INDEX idx_sbc_slot (slot_id) 既存（先頭一致）→ 追加不要。
--
-- ━━━ 対象外（本 migration では一切触らない）━━━
--   ・activity_results             → activity_templates（fk_ar_template・別軍）/ → schedules（fk_ar_schedule SET NULL・別軍）/ → users（fk_ar_created_by SET NULL）。
--   ・schedules                    → teams/organizations/users（fk_sch_team/fk_sch_org/fk_sch_user CASCADE）/ → users（fk_sch_created_by SET NULL）/ → schedules（fk_sch_parent RESTRICT・同一ドメイン）。
--                                   ※ fk_schedules_committee（→ committees）は 5-A で撤廃済。
--   ・analytics_daily_modules      → （module 以外の FK なし）。
--   ・chart_intake_forms           → chart_records（fk_cif_chart CASCADE・同一 chart ドメイン）。
--   ・disclosure_exports           → disclosure_form_templates（fk_de_template RESTRICT・同一 disclosure ドメイン）/ → disclosure_form_drafts（fk_de_draft SET NULL）/ → dwelling_units（fk_de_dwelling SET NULL・別軍）/ → users（fk_de_requester RESTRICT）/ → circulation_documents（fk_de_circulation SET NULL）。
--   ・events                       → users（fk_events_created_by SET NULL）。
--   ・shift_budget_allocations     → organizations（fk_sba_organization CASCADE）/ → teams（fk_sba_team CASCADE）/ → users（fk_sba_created_by RESTRICT）。
--   ・shift_budget_consumptions    → shift_budget_allocations（fk_sbc_allocation RESTRICT・同一 shift_budget ドメイン）/ → users（fk_sbc_user RESTRICT）。
--   上記11の「→ venues / module_definitions / electronic_seals / shared_files / schedules / budget_categories /
--   budget_fiscal_years / projects / shift_schedules / shift_slots」のみを DROP する。

-- ===== RESTRICT → venues 2件（venues は全テナント共通のマスタ・物理削除されない） =====

-- 1. activity_results.venue_id → venues (RESTRICT) クロスドメイン参照
--    撤廃only。venues はマスタで物理削除されず RESTRICT 発火不能。idx_ar_venue (先頭=当該列) 既存 → index 追加不要。
ALTER TABLE activity_results DROP FOREIGN KEY fk_ar_venue;

-- 2. schedules.venue_id → venues (RESTRICT) クロスドメイン参照
--    撤廃only。venues はマスタで物理削除されず RESTRICT 発火不能。idx_sch_venue (先頭=当該列) 既存 → index 追加不要。
ALTER TABLE schedules DROP FOREIGN KEY fk_sch_venue;

-- ===== RESTRICT → module_definitions 1件（マスタ・論理削除のみ） =====

-- 3. analytics_daily_modules.module_id → module_definitions (RESTRICT) クロスドメイン参照
--    撤廃only。module_definitions は論理削除（deleted_at）のみで RESTRICT 発火不能。module_id は date 主導クエリの冷たい列 → index 追加不要。
ALTER TABLE analytics_daily_modules DROP FOREIGN KEY fk_analytics_daily_modules_module;

-- ===== RESTRICT → electronic_seals 1件（論理削除のみ） =====

-- 4. chart_intake_forms.electronic_seal_id → electronic_seals (RESTRICT) クロスドメイン参照
--    撤廃only。electronic_seals は論理削除（deleted_at）のみで RESTRICT 発火不能。前向き参照の冷たい列 → index 追加不要。
ALTER TABLE chart_intake_forms DROP FOREIGN KEY fk_cif_seal;

-- ===== RESTRICT → shared_files 1件（論理削除のみ） =====

-- 5. disclosure_exports.shared_file_id → shared_files (RESTRICT) クロスドメイン参照
--    撤廃only。shared_files は論理削除（deleted_at）のみで RESTRICT 発火不能。前向き参照の冷たい列 → index 追加不要。
ALTER TABLE disclosure_exports DROP FOREIGN KEY fk_de_shared_file;

-- ===== RESTRICT → schedules 1件（論理削除のみ） =====

-- 6. events.schedule_id → schedules (RESTRICT) クロスドメイン参照
--    撤廃only。schedules は論理削除（deleted_at）のみで RESTRICT 発火不能。UNIQUE uq_events_schedule (先頭=当該列) 既存 → index 追加不要。
ALTER TABLE events DROP FOREIGN KEY fk_events_schedule;

-- ===== RESTRICT → budget_categories / budget_fiscal_years / projects 3件（論理削除のみ） =====

-- 7. shift_budget_allocations.budget_category_id → budget_categories (RESTRICT) クロスドメイン参照
--    撤廃only。budget_categories は論理削除（deleted_at）のみで RESTRICT 発火不能。予算費目マスタへの冷たい列 → index 追加不要。
ALTER TABLE shift_budget_allocations DROP FOREIGN KEY fk_sba_budget_category;

-- 8. shift_budget_allocations.fiscal_year_id → budget_fiscal_years (RESTRICT) クロスドメイン参照
--    撤廃only。budget_fiscal_years は論理削除（deleted_at）のみで RESTRICT 発火不能。idx_sba_fiscal (先頭=当該列) 既存 → index 追加不要。
ALTER TABLE shift_budget_allocations DROP FOREIGN KEY fk_sba_fiscal_year;

-- 9. shift_budget_allocations.project_id → projects (RESTRICT) クロスドメイン参照
--    撤廃only。projects は論理削除（deleted_at）のみで RESTRICT 発火不能。idx_sba_project (先頭=当該列) 既存 → index 追加不要。
ALTER TABLE shift_budget_allocations DROP FOREIGN KEY fk_sba_project;

-- ===== RESTRICT → shift_schedules / shift_slots 2件（論理削除/親追従のみ） =====

-- 10. shift_budget_consumptions.shift_id → shift_schedules (RESTRICT) クロスドメイン参照
--     撤廃only。shift_schedules は論理削除（deleted_at）のみで RESTRICT 発火不能。idx_sbc_shift (先頭=当該列) 既存 → index 追加不要。
ALTER TABLE shift_budget_consumptions DROP FOREIGN KEY fk_sbc_shift;

-- 11. shift_budget_consumptions.slot_id → shift_slots (RESTRICT) クロスドメイン参照
--     撤廃only。shift_slots は親 shift_schedules の論理削除に追従し本番で単独物理削除されず RESTRICT 発火不能。idx_sbc_slot (先頭=当該列) 既存 → index 追加不要。
ALTER TABLE shift_budget_consumptions DROP FOREIGN KEY fk_sbc_slot;
