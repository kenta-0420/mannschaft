-- Phase 4-C（第四陣C）: クロスドメインFK撤廃 — survey / activity_result / budget_transaction / incident /
-- reservation_line / project / team_template / confirmable_notification を「参照先テーブル」とする
-- SET NULL 構造FK 8件（単発・各ドメインに1〜2件ずつ散在）を撤廃（撤廃only・孤児保持）。
--
-- 1000万ユーザー耐久DB再構築 クロスドメインFK撤廃キャンペーン Phase 4-C。
-- CLAUDE.md §1「クロスドメインFKは作らない」/ §2「CASCADE/SET NULL のクロスドメイン削除連鎖は禁止」
-- 原則に従い、他ドメインの「テーブル」（users ではない）を ON DELETE SET NULL で参照する
-- 群2＝構造参照のクロスドメインFK 8件を撤廃する。
--
-- ━━━ 群2（本陣の対象）の定義 ━━━
-- 第三陣までの群1（users 親の監査列 SET NULL）と異なり、本陣の群2は「他ドメインの実テーブルの行が
-- 削除された時に SET NULL される構造参照」である。退会フローとは無関係で、参照先テーブルの行削除が
-- トリガになる。第四陣A（V109.001）＝schedules/todos/timeline_posts 参照、第四陣B（V110.001）＝
-- visibility_templates/workflow_templates/workflow_requests/dwelling_units 参照に続く第三弾。
-- 本 PR-4c は「単発で各ドメインに散在する 8件」を束ねて一掃する。
--
-- ━━━ 対象一覧（8件・すべて明示名・すべて SET NULL → 他ドメインのテーブル）━━━
--  1. events                    / fk_events_pre_survey   (pre_survey_id          → surveys                  SET NULL)
--  2. performance_records       / fk_pr_activity         (activity_result_id     → activity_results         SET NULL)
--  3. property_work_packages    / fk_pwp_budget_tx       (budget_transaction_id  → budget_transactions      SET NULL)
--  4. property_work_packages    / fk_pwp_incident        (incident_id            → incidents                SET NULL)
--  5. recruitment_listings      / fk_rl_reservation_line (reservation_line_id    → reservation_lines        SET NULL)
--  6. shift_schedules           / fk_ss_linked_project   (linked_project_id      → projects                 SET NULL)
--  7. teams                     / fk_teams_template      (template_id            → team_templates           SET NULL)
--  8. committee_distribution_logs / fk_cdl_confirmable   (confirmable_notification_id → confirmable_notifications SET NULL)
--
-- ━━━ なぜ「撤廃only・孤児値保持・リスナー/データ操作なし」が安全か ━━━
--
-- これら8件はすべて ON DELETE SET NULL であり、参照先テーブルの行が「物理削除」された場合にのみ
-- 参照元の外部キー列が NULL 化される構造である。撤廃の安全性は参照先ドメインごとに次の通り（家老偵察で
-- エンティティ実装まで裏取り済）。いずれも論理削除 or status 遷移のみで運用され物理削除されない
-- ＝ ON DELETE SET NULL が発火する契機が存在しない ＝ 撤廃は無条件に安全（第四陣A/B と同じ論理）:
--
--   ・surveys                  : SurveyEntity が deleted_at + @SQLRestriction("deleted_at IS NULL") で論理削除のみ。
--   ・activity_results         : ActivityResultEntity が deleted_at + @SQLRestriction で論理削除のみ。
--   ・budget_transactions      : BudgetTransactionEntity が deleted_at + @SQLRestriction で論理削除のみ。
--   ・incidents                : IncidentEntity が deleted_at + @SQLRestriction で論理削除のみ。
--   ・reservation_lines        : ReservationLineEntity が deleted_at + @SQLRestriction で論理削除のみ。
--   ・projects                 : ProjectEntity が deleted_at + softDelete() で論理削除のみ。
--   ・team_templates           : TeamTemplateEntity が deleted_at + @SQLRestriction で論理削除のみ。
--   ・confirmable_notifications: ConfirmableNotificationEntity は物理削除せず status を CANCELLED/EXPIRED/COMPLETED
--                                へ遷移させるライフサイクルのみ（物理削除メソッド無し）。
--
-- したがって本 migration は「純粋な FK 撤廃のみ」である。リスナー・データ操作・NULL 化処理は一切伴わない。
-- 参照整合性は今後アプリ層で保証する（CLAUDE.md §1）。番人テストが守る不変条件は
-- 「参照先テーブルの行を（テスト内で意図的に）物理 DELETE しても、参照元の外部キー列が NULL 化されず
--  孤児値を保持し続ける」ことであり、SET NULL 撤廃only の肝を直接検証する。
--
-- ━━━ index 判定（FK 撤廃で暗黙バッキング index が消えても CREATE INDEX 追加が必要か）━━━
--
-- 結論: 8件とも CREATE INDEX 追加は不要。
--  ・既に当該列を先頭に持つ非FK index があり FK の暗黙 index に依存しないもの（4件）:
--    2. performance_records.activity_result_id : INDEX idx_pr_activity (activity_result_id) 既存（先頭=当該列）→ 追加不要。
--    4. property_work_packages.incident_id     : INDEX idx_pwp_incident (incident_id) 既存（先頭=当該列）→ 追加不要。
--    5. recruitment_listings.reservation_line_id : INDEX idx_rl_line_overlap (reservation_line_id, start_at, end_at) 既存（先頭=当該列）→ 追加不要。
--    6. shift_schedules.linked_project_id      : INDEX idx_ss_linked_project (linked_project_id) 既存（先頭=当該列）→ 追加不要。
--  ・冷たい関連列で当該列を先頭にした非FK index も逆引き finder も存在しないもの（4件）:
--    1. events.pre_survey_id                   : pre_survey_id 先頭の専用 index なし・逆引き finder なし
--         （survey→event の逆引きクエリは存在しない。前向きに event 行からアンケート id を解決するのみ）→ クエリ証跡なし → 追加不要。
--    3. property_work_packages.budget_transaction_id : budget_transaction_id 先頭の専用 index なし・逆引き finder なし
--         （pwp は scope/status/work_type/dwelling/incident 軸で引かれ、budget_transaction→pwp の逆引きクエリ無し）→ 追加不要。
--    7. teams.template_id                      : template_id 先頭の専用 index なし・逆引き finder なし
--         （team は slug/id/scope で引かれ、template→team の逆引きクエリ無し）→ 追加不要。
--    8. committee_distribution_logs.confirmable_notification_id : 当該列先頭の専用 index なし・逆引き finder なし
--         （ログは committee_id/content 軸で引かれ、confirmable_notification→log の逆引きクエリ無し）→ 追加不要。
--
-- ━━━ 対象外（本 migration では一切触らない）━━━
--   ・各テーブルの scope（team_id/organization_id/user_id・scope_type/scope_id）・user・親（category/template 等）・
--     同一ドメインFK 等は対象外。
--   ・events → schedules（fk_events_schedule RESTRICT）/ → users（fk_events_created_by SET NULL）。
--   ・performance_records → users（fk_pr_user RESTRICT / fk_pr_recorded_by SET NULL）/ performance_metrics（fk_pr_metric_id）/
--     schedules（fk_pr_schedule は第四陣A V109.001 で撤廃済）。
--   ・property_work_packages → users（fk_pwp_created_by/updated_by）/ vendors（fk_pwp_vendor）/ dwelling_units（fk_pwp_dwelling は第四陣B V110.001 で撤廃済）/ timeline_posts（fk_pwp_timeline は第四陣A V109.001 で撤廃済）。
--   ・recruitment_listings → recruitment_categories（fk_rl_created_by/category）/ recruitment_subcategories（fk_rl_subcategory）/ users / visibility_templates（fk_recruitment_listings_vt は第四陣B V110.001 で撤廃済）。
--   ・shift_schedules → teams（fk_ss_team CASCADE・同一/scope）/ users（created_by/published_by SET NULL）。
--   ・teams → organizations（scope）/ users 等。
--   ・committee_distribution_logs → committees（fk_cdl_committee CASCADE・同一委員会ドメイン）/ users（fk_cdl_created_by SET NULL）。
--   上記8つの「SET NULL → surveys / activity_results / budget_transactions / incidents / reservation_lines /
--   projects / team_templates / confirmable_notifications」のみを DROP する。

-- ===== surveys（参照先・survey ドメイン）を参照するクロスドメイン SET NULL FK 1件 =====

-- 1. events.pre_survey_id → surveys (SET NULL) クロスドメイン構造参照
--    撤廃only。surveys は論理削除（@SQLRestriction deleted_at）のみで SET NULL 発火不能。冷たい関連列・逆引き finder なし → index 追加不要。
ALTER TABLE events DROP FOREIGN KEY fk_events_pre_survey;

-- ===== activity_results（参照先・activity ドメイン）を参照するクロスドメイン SET NULL FK 1件 =====

-- 2. performance_records.activity_result_id → activity_results (SET NULL) クロスドメイン構造参照
--    撤廃only。activity_results は論理削除（@SQLRestriction deleted_at）のみで SET NULL 発火不能。INDEX idx_pr_activity 既存（先頭=当該列）→ index 追加不要。
ALTER TABLE performance_records DROP FOREIGN KEY fk_pr_activity;

-- ===== budget_transactions（参照先・budget ドメイン）を参照するクロスドメイン SET NULL FK 1件 =====

-- 3. property_work_packages.budget_transaction_id → budget_transactions (SET NULL) クロスドメイン構造参照
--    撤廃only。budget_transactions は論理削除（@SQLRestriction deleted_at）のみで SET NULL 発火不能。冷たい関連列・逆引き finder なし → index 追加不要。
ALTER TABLE property_work_packages DROP FOREIGN KEY fk_pwp_budget_tx;

-- ===== incidents（参照先・incident ドメイン）を参照するクロスドメイン SET NULL FK 1件 =====

-- 4. property_work_packages.incident_id → incidents (SET NULL) クロスドメイン構造参照
--    撤廃only。incidents は論理削除（@SQLRestriction deleted_at）のみで SET NULL 発火不能。INDEX idx_pwp_incident 既存（先頭=当該列）→ index 追加不要。
ALTER TABLE property_work_packages DROP FOREIGN KEY fk_pwp_incident;

-- ===== reservation_lines（参照先・reservation ドメイン）を参照するクロスドメイン SET NULL FK 1件 =====

-- 5. recruitment_listings.reservation_line_id → reservation_lines (SET NULL) クロスドメイン構造参照
--    撤廃only。reservation_lines は論理削除（@SQLRestriction deleted_at）のみで SET NULL 発火不能。INDEX idx_rl_line_overlap 既存（先頭=当該列）→ index 追加不要。
ALTER TABLE recruitment_listings DROP FOREIGN KEY fk_rl_reservation_line;

-- ===== projects（参照先・todo/project ドメイン）を参照するクロスドメイン SET NULL FK 1件 =====

-- 6. shift_schedules.linked_project_id → projects (SET NULL) クロスドメイン構造参照
--    撤廃only。projects は論理削除（deleted_at + softDelete）のみで SET NULL 発火不能。INDEX idx_ss_linked_project 既存（先頭=当該列）→ index 追加不要。
ALTER TABLE shift_schedules DROP FOREIGN KEY fk_ss_linked_project;

-- ===== team_templates（参照先・template ドメイン）を参照するクロスドメイン SET NULL FK 1件 =====

-- 7. teams.template_id → team_templates (SET NULL) クロスドメイン構造参照
--    撤廃only。team_templates は論理削除（@SQLRestriction deleted_at）のみで SET NULL 発火不能。冷たい関連列・逆引き finder なし → index 追加不要。
ALTER TABLE teams DROP FOREIGN KEY fk_teams_template;

-- ===== confirmable_notifications（参照先・notification ドメイン）を参照するクロスドメイン SET NULL FK 1件 =====

-- 8. committee_distribution_logs.confirmable_notification_id → confirmable_notifications (SET NULL) クロスドメイン構造参照
--    撤廃only。confirmable_notifications は status 遷移（CANCELLED/EXPIRED/COMPLETED）のみで物理削除されず SET NULL 発火不能。冷たい関連列・逆引き finder なし → index 追加不要。
ALTER TABLE committee_distribution_logs DROP FOREIGN KEY fk_cdl_confirmable;
