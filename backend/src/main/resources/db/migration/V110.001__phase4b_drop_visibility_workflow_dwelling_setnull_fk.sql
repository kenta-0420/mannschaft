-- Phase 4-B（第四陣B）: クロスドメインFK撤廃 — visibility_templates / workflow_templates / workflow_requests / dwelling_units を「参照先テーブル」とする SET NULL 構造FK10件を撤廃（撤廃only・孤児保持）
--
-- 1000万ユーザー耐久DB再構築 クロスドメインFK撤廃キャンペーン Phase 4-B。
-- CLAUDE.md §1「クロスドメインFKは作らない」/ §2「CASCADE/SET NULL のクロスドメイン削除連鎖は禁止」
-- 原則に従い、他ドメインの「テーブル」（users ではない）を ON DELETE SET NULL で参照する
-- 群2＝構造参照のクロスドメインFK 10件を撤廃する。
--
-- ━━━ 群2（本陣の対象）の定義 ━━━
-- 第三陣までの群1（users 親の監査列 SET NULL）と異なり、本陣の群2は「他ドメインの実テーブル
-- （visibility_templates / workflow_templates / workflow_requests / dwelling_units）の行が削除された時に
-- SET NULL される構造参照」である。退会フローとは無関係で、参照先テーブルの行削除がトリガになる。
-- 第四陣A（V109.001）が schedules / todos / timeline_posts 参照の8件を撤廃したのに続く第二弾。
--
-- ━━━ 対象一覧（10件・すべて明示名・すべて SET NULL → 他ドメインのテーブル）━━━
--  ◆ visibility_templates（visibility ドメイン）を参照する 3件:
--   1. blog_posts            / fk_blog_posts_vt           (visibility_template_id → visibility_templates SET NULL)
--   2. recruitment_listings  / fk_recruitment_listings_vt (visibility_template_id → visibility_templates SET NULL)
--   3. schedules             / fk_schedules_vt            (visibility_template_id → visibility_templates SET NULL)
--  ◆ workflow_templates（workflow ドメイン）を参照する 2件:
--   4. budget_configs        / fk_bconf_over_limit_workflow (over_limit_workflow_id → workflow_templates SET NULL)
--   5. budget_configs        / fk_bconf_workflow_template   (workflow_template_id   → workflow_templates SET NULL)
--  ◆ workflow_requests（workflow ドメイン）を参照する 2件:
--   6. budget_threshold_alerts / fk_bta_workflow_request   (workflow_request_id → workflow_requests SET NULL)
--   7. budget_transactions     / fk_bt_workflow_request    (workflow_request_id → workflow_requests SET NULL)
--  ◆ dwelling_units（residence/property ドメイン）を参照する 3件:
--   8. disclosure_exports      / fk_de_dwelling   (target_dwelling_unit_id → dwelling_units SET NULL)
--   9. disclosure_form_drafts  / fk_dfd_dwelling  (target_dwelling_unit_id → dwelling_units SET NULL)
--  10. property_work_packages  / fk_pwp_dwelling  (dwelling_unit_id        → dwelling_units SET NULL)
--
-- ━━━ なぜ「撤廃only・孤児値保持・リスナー/データ操作なし」が安全か ━━━
--
-- これら10件はすべて ON DELETE SET NULL であり、参照先テーブルの行が「物理削除」された場合にのみ
-- 参照元の外部キー列が NULL 化される構造である。撤廃の安全性は参照先ドメインごとに次の通り（家老偵察で裏取り済）:
--
--  (A) workflow_templates / workflow_requests（4・5・6・7番）:
--    いずれも論理削除のみで運用される。
--      ・WorkflowTemplateEntity : deleted_at 列を持ち、論理削除（deleted_at セット）のみ。
--      ・WorkflowRequestEntity   : deleted_at 列を持ち、@SQLRestriction("deleted_at IS NULL") で論理削除。
--    DELETE FROM による行の物理削除は運用上発生しない → ON DELETE SET NULL が発火する契機が存在しない
--    → 撤廃は無条件に安全（第四陣A の schedules/todos/timeline_posts と同じ論理）。
--
--  (B) dwelling_units（8・9・10番）:
--    DwellingUnitEntity は deleted_at 列を持ち論理削除のみで運用される。物理削除されない
--    → ON DELETE SET NULL が発火する契機が存在しない → 撤廃は無条件に安全。
--
--  (C) visibility_templates（1・2・3番）:
--    visibility_templates は owner_user_id へ ON DELETE CASCADE を持つため「ユーザー退会で行が物理削除」
--    され得る（論理削除のみではない）。しかし VisibilityTemplateEvaluator は visibility_template_id を
--    findById で解決し、empty（=孤児・参照先消失）の場合は権限を「拒否（fail-closed）」する設計である。
--    したがって SET NULL で NULL 化されようと、孤児値が残ろうと、いずれも「テンプレ未解決＝拒否」に収束し、
--    孤児化による権限漏洩（過剰可視）は発生しない → 撤廃は安全。
--    むしろ SET NULL を撤廃して孤児値を保持する方が、後日テンプレが復元されれば設定が蘇る点で
--    情報量を失わない（撤廃only・孤児保持の方針に合致）。
--
-- したがって本 migration は「純粋な FK 撤廃のみ」である。リスナー・データ操作・NULL 化処理は一切伴わない。
-- 参照整合性は今後アプリ層で保証する（CLAUDE.md §1）。番人テストが守る不変条件は
-- 「参照先テーブルの行を（テスト内で意図的に）物理 DELETE しても、参照元の外部キー列が NULL 化されず
--  孤児値を保持し続ける」ことであり、SET NULL 撤廃only の肝を直接検証する。
--
-- ━━━ index 判定（FK 撤廃で暗黙バッキング index が消えても CREATE INDEX 追加が必要か）━━━
--
-- 結論: 10件とも CREATE INDEX 追加は不要。
--  ・既に当該列を先頭に持つ非FK index があり FK の暗黙 index に依存しないもの（3件）:
--    8. disclosure_exports.target_dwelling_unit_id     : INDEX idx_de_dwelling (target_dwelling_unit_id) 既存（先頭=当該列）→ 追加不要。
--    9. disclosure_form_drafts.target_dwelling_unit_id : INDEX idx_dfd_dwelling (target_dwelling_unit_id) 既存（先頭=当該列）→ 追加不要。
--   10. property_work_packages.dwelling_unit_id        : INDEX idx_pwp_dwelling (dwelling_unit_id) 既存（先頭=当該列）→ 追加不要。
--  ・冷たい関連列で当該列を先頭にした非FK index も逆引き finder も存在しないもの（7件）:
--    1. blog_posts.visibility_template_id           : visibility_template_id 先頭の専用 index なし・逆引き finder なし
--         （可視性テンプレ→記事の逆引きクエリは存在しない。前向きにテンプレ id で解決するのみ）→ クエリ証跡なし → 追加不要。
--    2. recruitment_listings.visibility_template_id : 同上（冷たい関連列・逆引きなし）→ 追加不要。
--    3. schedules.visibility_template_id            : 同上（冷たい関連列・逆引きなし）→ 追加不要。
--    4. budget_configs.over_limit_workflow_id       : over_limit_workflow_id 先頭の専用 index なし・逆引き finder なし
--         （budget_configs は scope で一意・workflow→config の逆引きクエリ無し）→ 追加不要。
--    5. budget_configs.workflow_template_id         : 同上 → 追加不要。
--    6. budget_threshold_alerts.workflow_request_id : workflow_request_id 先頭の専用 index なし・逆引き finder なし
--         （alert は allocation 軸で引かれ、workflow_request→alert の逆引きクエリ無し）→ 追加不要。
--    7. budget_transactions.workflow_request_id     : workflow_request_id 先頭の専用 index なし・逆引き finder なし
--         （transaction は fiscal_year/category/scope/date 軸で引かれ、workflow_request→tx の逆引き無し）→ 追加不要。
--
-- ━━━ 対象外（本 migration では一切触らない）━━━
--   ・各テーブルの scope（team_id/organization_id/user_id・scope_type/scope_id）・user・親（template/category 等）・
--     同一ドメインFK 等は対象外。
--   ・blog_posts → teams/organizations/users（fk_bp_team/org/user CASCADE スコープ）/ → users(fk_bp_author SET NULL)
--     / → blog_post_series（fk_bp_series 同一 CMS ドメイン）。
--   ・recruitment_listings → recruitment_categories/subcategories/reservation_lines/users（同一/別ドメインだが本 PR 対象外）。
--   ・schedules → teams/organizations/users/schedules（自己参照）/ visibility_templates 以外のFK。
--   ・budget_configs → budget_categories（fk_bconf_default_income_category SET NULL・同一 budget ドメイン）。
--   ・budget_threshold_alerts → shift_budget_allocations（fk_bta_allocation CASCADE・同一 budget ドメイン）/ → users（fk_bta_acked_by は第三陣F V107.001 で撤廃済）。
--   ・budget_transactions → budget_fiscal_years/budget_categories/budget_transactions（自己参照）/users（同一/別ドメインだが本 PR 対象外）。
--   ・disclosure_exports → disclosure_form_drafts/disclosure_form_templates/shared_files/users/circulation_documents（本 PR 対象外）。
--   ・disclosure_form_drafts → disclosure_form_templates/users（本 PR 対象外）。
--   ・property_work_packages → incidents/vendors/budget_transactions/users（fk_pwp_incident/vendor/budget_tx 等）/ timeline_posts（fk_pwp_timeline は第四陣A V109.001 で撤廃済）。
--   上記10つの「SET NULL → visibility_templates / workflow_templates / workflow_requests / dwelling_units」のみを DROP する。

-- ===== visibility_templates（参照先）を参照するクロスドメイン SET NULL FK 3件 =====

-- 1. blog_posts.visibility_template_id → visibility_templates (SET NULL) クロスドメイン構造参照
--    撤廃only。Evaluator が findById empty→拒否(fail-closed)のため孤児でも権限漏洩なし。冷たい関連列・逆引き finder なし → index 追加不要。
ALTER TABLE blog_posts DROP FOREIGN KEY fk_blog_posts_vt;

-- 2. recruitment_listings.visibility_template_id → visibility_templates (SET NULL) クロスドメイン構造参照
--    撤廃only。fail-closed のため孤児でも権限漏洩なし。冷たい関連列・逆引き finder なし → index 追加不要。
ALTER TABLE recruitment_listings DROP FOREIGN KEY fk_recruitment_listings_vt;

-- 3. schedules.visibility_template_id → visibility_templates (SET NULL) クロスドメイン構造参照
--    撤廃only。fail-closed のため孤児でも権限漏洩なし。冷たい関連列・逆引き finder なし → index 追加不要。
ALTER TABLE schedules DROP FOREIGN KEY fk_schedules_vt;

-- ===== workflow_templates（参照先）を参照するクロスドメイン SET NULL FK 2件 =====

-- 4. budget_configs.over_limit_workflow_id → workflow_templates (SET NULL) クロスドメイン構造参照
--    撤廃only。workflow_templates は論理削除のみで SET NULL 発火不能。冷たい関連列・逆引き finder なし → index 追加不要。
ALTER TABLE budget_configs DROP FOREIGN KEY fk_bconf_over_limit_workflow;

-- 5. budget_configs.workflow_template_id → workflow_templates (SET NULL) クロスドメイン構造参照
--    撤廃only。workflow_templates は論理削除のみで SET NULL 発火不能。冷たい関連列・逆引き finder なし → index 追加不要。
ALTER TABLE budget_configs DROP FOREIGN KEY fk_bconf_workflow_template;

-- ===== workflow_requests（参照先）を参照するクロスドメイン SET NULL FK 2件 =====

-- 6. budget_threshold_alerts.workflow_request_id → workflow_requests (SET NULL) クロスドメイン構造参照
--    撤廃only。workflow_requests は論理削除（@SQLRestriction deleted_at）のみで SET NULL 発火不能。冷たい関連列・逆引き finder なし → index 追加不要。
ALTER TABLE budget_threshold_alerts DROP FOREIGN KEY fk_bta_workflow_request;

-- 7. budget_transactions.workflow_request_id → workflow_requests (SET NULL) クロスドメイン構造参照
--    撤廃only。workflow_requests は論理削除のみで SET NULL 発火不能。冷たい関連列・逆引き finder なし → index 追加不要。
ALTER TABLE budget_transactions DROP FOREIGN KEY fk_bt_workflow_request;

-- ===== dwelling_units（参照先）を参照するクロスドメイン SET NULL FK 3件 =====

-- 8. disclosure_exports.target_dwelling_unit_id → dwelling_units (SET NULL) クロスドメイン構造参照
--    撤廃only。dwelling_units は論理削除のみで SET NULL 発火不能。INDEX idx_de_dwelling 既存（先頭=当該列）→ index 追加不要。
ALTER TABLE disclosure_exports DROP FOREIGN KEY fk_de_dwelling;

-- 9. disclosure_form_drafts.target_dwelling_unit_id → dwelling_units (SET NULL) クロスドメイン構造参照
--    撤廃only。dwelling_units は論理削除のみで SET NULL 発火不能。INDEX idx_dfd_dwelling 既存（先頭=当該列）→ index 追加不要。
ALTER TABLE disclosure_form_drafts DROP FOREIGN KEY fk_dfd_dwelling;

-- 10. property_work_packages.dwelling_unit_id → dwelling_units (SET NULL) クロスドメイン構造参照
--    撤廃only。dwelling_units は論理削除のみで SET NULL 発火不能。INDEX idx_pwp_dwelling 既存（先頭=当該列）→ index 追加不要。
ALTER TABLE property_work_packages DROP FOREIGN KEY fk_pwp_dwelling;
