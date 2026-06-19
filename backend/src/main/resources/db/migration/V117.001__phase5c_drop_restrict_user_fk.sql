-- Phase 5-C（最終局面・第三弾＝本丸）: クロスドメインFK撤廃 — 「RESTRICT → users（監査/作成者/操作者列）」25件を撤廃only（孤児保持）。
--
-- 1000万ユーザー耐久DB再構築 クロスドメインFK撤廃キャンペーン Phase 5-C。
-- CLAUDE.md §1「クロスドメインFKは作らない」/ §2「CASCADE/SET NULL のクロスドメイン削除連鎖は禁止」原則に従い、
-- 各ドメインの実テーブルが users（user ドメイン）を参照する FK を撤廃する。
-- 第一〜四陣で「user/team/org 親の CASCADE」「群1=users 親 SET NULL」「群2=他テーブル SET NULL」を全廃し、
-- 最終局面 5-A（V114.001）で「発火不能群」12件、5-B（V115.001）で「RESTRICT → org/team 以外の他ドメイン実テーブル」11件を撤廃済。
-- 本陣（最終局面 5-C）が対象とするのは「残った RESTRICT のうち、参照先が users である監査/作成者/操作者カラムのFK」25件である
-- （net-active な RESTRICT→users FK は baseline 上で 25 件。撤廃後 baseline は 27→2 となり、残る2件は
--  schedule_attendances/fk_sa_proxy_record・survey_responses/fk_sr_proxy_record＝→proxy_input_records の別系統で、最終局面 5-D の対象）。
--
-- ━━━ なぜ「撤廃only・孤児保持・リスナー/データ操作/NULL化なし」が本 PR の本丸なのか（退会 purge ブロック解消）━━━
-- これらの FK は「created_by / approved_by / user_id / redeemed_by / listed_by / uploaded_by / 代理同意の本人・代理者・立会人」等、
-- user ドメインの users を ON DELETE RESTRICT で参照する。RESTRICT が残っている限り、当該 user を参照する子行が1件でも存在すると
-- users の物理削除は MySQL によってブロックされる。
--
-- ここで退会の物理削除フロー（AccountPurgeService.purgeUser → userRepository.delete(...)）に注目する。
-- 退会の匿名化イベント（UserAnonymizedEvent）の AFTER_COMMIT リスナー群が子データを片付けるより前に、
-- もしくはリスナーの守備範囲外の子データが残っていると、userRepository.delete(...) が
-- これら RESTRICT 子（coupons.created_by / promotions.created_by / job_postings.created_by_user_id /
-- reservations.user_id / proxy_input_consents.subject_user_id 等）にブロックされ、退会が滞留し得る潜在バグがある。
--
-- 本 PR で RESTRICT を撤廃すると、子行は created_by / approved_by 等に「孤児となった user_id 値」を保持したまま生存し、
-- AccountPurgeService の物理 DELETE は RESTRICT に阻まれず貫通する。
-- これは CLAUDE.md §4「ユーザー退会時は匿名化（削除しない）／投稿・履歴・統計は user_id を NULL にせずそのまま残す」方針とも完全に一致する
-- （監査・履歴の価値を保持しつつ、退会の物理削除を貫通させる）。参照整合性は今後アプリ層で保証する（CLAUDE.md §1）。
--
-- 本 migration は「純粋な FK 撤廃のみ」である。リスナー・データ操作・NULL 化処理は一切伴わない。
-- 番人テスト（FlywayExistingDataRestrictUserAuditFkMigrationTest）が守る不変条件は
-- 「参照元の子行をシードし、参照先の users 行を（テスト内で）物理 DELETE しても、
--  RESTRICT が撤廃済みゆえ users DELETE がブロックされず、子行は孤児 user_id 値を保持し続ける」ことであり、
-- 退会 purge ブロック解消の肝を直接検証する。
--
-- ━━━ 対象一覧（25件・すべて明示名・すべて既定 ON DELETE RESTRICT → users）━━━
--  1. advertiser_accounts             / fk_advertiser_accounts_approved_by (approved_by               → users)
--  2. attendance_requirement_evaluations / fk_are_resolver                 (resolver_user_id          → users)
--  3. coupon_redemptions              / fk_cr_redeemed_by                  (redeemed_by               → users)
--  4. coupons                         / fk_coupons_created_by              (created_by                → users)
--  5. job_postings                    / fk_jp_creator                      (created_by_user_id        → users)
--  6. performance_records             / fk_pr_user                         (user_id                   → users)
--  7. promotions                      / fk_promotions_created_by           (created_by                → users)
--  8. property_listings               / fk_pl_listed_by                    (listed_by                 → users)
--  9. proxy_input_consents            / fk_pic_subject                     (subject_user_id           → users)
-- 10. proxy_input_consents            / fk_pic_proxy                       (proxy_user_id             → users)
-- 11. proxy_input_consents            / fk_pic_witness                     (witness_user_id           → users)
-- 12. proxy_input_consents            / fk_pic_approved_by                 (approved_by_user_id       → users)
-- 13. proxy_input_consents            / fk_pic_revoke_wit                  (revoke_witnessed_by_user_id → users)
-- 14. recruitment_cancellation_policies / fk_rcp_created_by                (created_by                → users)
-- 15. recruitment_listings            / fk_rl_created_by                   (created_by                → users)
-- 16. recruitment_participants        / fk_rp_user                         (user_id                   → users)
-- 17. recruitment_subcategories       / fk_rs_created_by                   (created_by                → users)
-- 18. reservations                    / fk_reservations_user               (user_id                   → users)
-- 19. resident_documents              / fk_rd_uploaded_by                  (uploaded_by               → users)
-- 20. saved_segment_presets           / fk_ssp_created_by                  (created_by                → users)
-- 21. shift_budget_allocations        / fk_sba_created_by                  (created_by                → users)
-- 22. shift_budget_consumptions       / fk_sbc_user                        (user_id                   → users)
-- 23. tags                            / fk_tags_created_by                 (created_by                → users)
-- 24. todo_budget_links               / fk_tbl_creator                     (created_by                → users)
-- 25. todo_handoffs                   / fk_handoff_from_user               (from_user_id              → users)
--
-- 注: proxy_input_consents は1テーブルで users RESTRICT FK を 5 件持つため、表上は5行（9〜13）。
--     DROP FOREIGN KEY 文は各 FK 名ごとに1文発行するので合計 25 文となる。
--     （= advertiser/are/cr/coupons/jp/pr/promotions/pl の 8 件 + proxy_input_consents の 5 件 + rcp/rl/rp/rs/reservations/rd/ssp/sba/sbc/tags/tbl/handoff の 12 件 = 25 件）
--
-- ━━━ index 判定（FK 撤廃で暗黙バッキング index が消えても CREATE INDEX 追加が必要か）━━━
-- 結論: 25件とも CREATE INDEX 追加は不要。理由は2系統:
--   (A) 撤廃対象列を「先頭」に持つ非FK index / UNIQUE が既存 → バッキング index は消えても検索 index が残る。
--   (B) 撤廃対象列が「冷たい監査/作成者/操作者列」で、当該列を先頭にした逆引き finder が存在しない（users への前向き参照のみ）
--       → バッキング index が消えても再作成不要。
-- 各列の判定:
--  1. advertiser_accounts.approved_by              : (B) 承認者の監査列。approved_by 先頭の逆引き finder なし（前向き参照）→ 追加不要。
--  2. attendance_requirement_evaluations.resolver_user_id : (B) 違反解消者の監査列。resolver 先頭の逆引きなし（検索は student/rule/status 主導）→ 追加不要。
--  3. coupon_redemptions.redeemed_by               : (B) 利用者の監査列。検索は distribution 主導（idx_cr_dist）。redeemed_by 先頭の逆引きなし → 追加不要。
--  4. coupons.created_by                           : (B) 作成者の監査列。検索は scope 主導（idx_coupons_scope）。created_by 先頭の逆引きなし → 追加不要。
--  5. job_postings.created_by_user_id              : (B) 作成者の監査列。検索は team/status 主導（idx_jp_team_status 等）。created_by 先頭の逆引きなし → 追加不要。
--  6. performance_records.user_id                  : (A) INDEX idx_pr_user_date (user_id, recorded_date DESC) 既存（先頭一致）→ 追加不要。
--  7. promotions.created_by                        : (B) 作成者の監査列。検索は scope/status 主導（idx_promotions_scope / idx_promotions_status）。created_by 先頭の逆引きなし → 追加不要。
--  8. property_listings.listed_by                  : (B) 出品者の監査列。検索は dwelling_unit/status 主導（idx_pl_unit / idx_pl_scope_status）。listed_by 先頭の逆引きなし → 追加不要。
--  9. proxy_input_consents.subject_user_id         : (A) INDEX idx_pic_subject (subject_user_id, effective_until) 既存（先頭一致）→ 追加不要。
-- 10. proxy_input_consents.proxy_user_id           : (A) INDEX idx_pic_proxy (proxy_user_id, effective_until) 既存（先頭一致）→ 追加不要。
-- 11. proxy_input_consents.witness_user_id         : (B) 立会人の監査列。witness 先頭の逆引き finder なし（検索は subject/proxy/org 主導）→ 追加不要。
-- 12. proxy_input_consents.approved_by_user_id     : (B) 承認者の監査列。approved_by 先頭の逆引き finder なし → 追加不要。
-- 13. proxy_input_consents.revoke_witnessed_by_user_id : (B) 撤回立会人の監査列。revoke_wit 先頭の逆引き finder なし → 追加不要。
-- 14. recruitment_cancellation_policies.created_by : (B) 作成者の監査列。検索は scope/template 主導（idx_rcp_scope / idx_rcp_template_flag）。created_by 先頭の逆引きなし → 追加不要。
-- 15. recruitment_listings.created_by              : (A) INDEX idx_rl_created_by (created_by) 既存（先頭一致）→ 追加不要。
-- 16. recruitment_participants.user_id             : (A) INDEX idx_rp_user_status (user_id, status, applied_at) 既存（先頭一致）→ 追加不要。
-- 17. recruitment_subcategories.created_by         : (B) 作成者の監査列。検索は scope 主導（idx_rs_scope）。created_by 先頭の逆引きなし → 追加不要。
-- 18. reservations.user_id                         : (A) INDEX idx_reservations_user_status_booked (user_id, status, booked_at) 既存（先頭一致）→ 追加不要。
-- 19. resident_documents.uploaded_by               : (B) アップロード者の監査列。検索は resident 主導（idx_rd_resident）。uploaded_by 先頭の逆引きなし → 追加不要。
-- 20. saved_segment_presets.created_by             : (B) 作成者の監査列。検索は scope 主導（idx_ssp_scope）。created_by 先頭の逆引きなし → 追加不要。
-- 21. shift_budget_allocations.created_by          : (B) 作成者の監査列。検索は org/team/project/fiscal 主導。created_by 先頭の逆引きなし → 追加不要。
-- 22. shift_budget_consumptions.user_id            : (A) INDEX idx_sbc_user_recorded (user_id, recorded_at) 既存（先頭一致）→ 追加不要。
-- 23. tags.created_by                              : (A) INDEX idx_tags_created_by (created_by) 既存（先頭一致）→ 追加不要。
-- 24. todo_budget_links.created_by                 : (B) 作成者の監査列。検索は project/todo/allocation 主導（idx_tbl_*）。created_by 先頭の逆引きなし → 追加不要。
-- 25. todo_handoffs.from_user_id                   : (A) INDEX idx_handoff_from_user (from_user_id, created_at DESC) 既存（先頭一致）→ 追加不要。
--
-- ━━━ 対象外（本 migration では一切触らない）━━━
--   各テーブルの「同一ドメイン内 FK」「scope/親への FK」「他の user FK で既に SET NULL 撤廃済みの監査列」は触らない。
--   例:
--    ・advertiser_accounts → organizations（fk_advertiser_accounts_organization・別ドメインだが本 PR 対象外＝別軍）。
--    ・performance_records → performance_metrics（fk_pr_metric RESTRICT・同一 performance ドメイン）/ → schedules（fk_pr_schedule SET NULL・別軍）/
--                            → activity_results（fk_pr_activity SET NULL・別軍）/ → users（fk_pr_recorded_by SET NULL・第三陣で撤廃済）。
--    ・promotions → users（fk_promotions_approved_by SET NULL・第三陣F V107.001 で撤廃済）。
--    ・recruitment_listings → recruitment_categories（fk_rl_category RESTRICT・同一ドメイン）/ → users（fk_rl_cancelled_by SET NULL・第三陣A で撤廃済）等。
--    ・recruitment_participants → teams（fk_rp_team RESTRICT・別軍）/ → users（fk_rp_applied_by / fk_rp_cancelled_by SET NULL・第三陣A で撤廃済）。
--    ・reservations → reservation_slots / reservation_lines（RESTRICT・同一 reservation ドメイン）/ → teams（fk_reservations_team CASCADE）。
--    ・shift_budget_allocations → budget_fiscal_years / budget_categories（RESTRICT・5-B V115.001 で撤廃済）/ → organizations / teams（CASCADE）。
--    ・shift_budget_consumptions → shift_budget_allocations（fk_sbc_allocation RESTRICT・同一 shift_budget ドメイン）/ → shift_schedules / shift_slots（5-B で撤廃済）。
--    ・todo_budget_links → projects / todos（CASCADE）/ → shift_budget_allocations（fk_tbl_allocation RESTRICT・同一ドメイン）。
--    ・todo_handoffs → todos（fk_handoff_todo CASCADE）/ → todo_status_labels（SET NULL）。
--   上記の「users を親とする ON DELETE RESTRICT」25件のみを DROP する。

-- ===== advertiser ドメイン =====

-- 1. advertiser_accounts.approved_by → users (RESTRICT) クロスドメイン参照（承認者監査列）
--    撤廃only。前向き監査列で逆引き finder なし → index 追加不要。
ALTER TABLE advertiser_accounts DROP FOREIGN KEY fk_advertiser_accounts_approved_by;

-- ===== attendance ドメイン =====

-- 2. attendance_requirement_evaluations.resolver_user_id → users (RESTRICT) クロスドメイン参照（違反解消者監査列）
--    撤廃only。前向き監査列で逆引き finder なし（検索は student/rule/status 主導）→ index 追加不要。
ALTER TABLE attendance_requirement_evaluations DROP FOREIGN KEY fk_are_resolver;

-- ===== coupon / promotion ドメイン =====

-- 3. coupon_redemptions.redeemed_by → users (RESTRICT) クロスドメイン参照（利用者監査列）
--    撤廃only。検索は distribution 主導（idx_cr_dist）で redeemed_by 先頭の逆引きなし → index 追加不要。
ALTER TABLE coupon_redemptions DROP FOREIGN KEY fk_cr_redeemed_by;

-- 4. coupons.created_by → users (RESTRICT) クロスドメイン参照（作成者監査列）
--    撤廃only。検索は scope 主導（idx_coupons_scope）で created_by 先頭の逆引きなし → index 追加不要。
ALTER TABLE coupons DROP FOREIGN KEY fk_coupons_created_by;

-- 7. promotions.created_by → users (RESTRICT) クロスドメイン参照（作成者監査列）
--    撤廃only。検索は scope/status 主導で created_by 先頭の逆引きなし → index 追加不要。
ALTER TABLE promotions DROP FOREIGN KEY fk_promotions_created_by;

-- 20. saved_segment_presets.created_by → users (RESTRICT) クロスドメイン参照（作成者監査列）
--     撤廃only。検索は scope 主導（idx_ssp_scope）で created_by 先頭の逆引きなし → index 追加不要。
ALTER TABLE saved_segment_presets DROP FOREIGN KEY fk_ssp_created_by;

-- ===== job ドメイン =====

-- 5. job_postings.created_by_user_id → users (RESTRICT) クロスドメイン参照（作成者監査列）
--    撤廃only。検索は team/status 主導で created_by 先頭の逆引きなし → index 追加不要。
ALTER TABLE job_postings DROP FOREIGN KEY fk_jp_creator;

-- ===== performance ドメイン =====

-- 6. performance_records.user_id → users (RESTRICT) クロスドメイン参照（記録対象者）
--    撤廃only。idx_pr_user_date (user_id, ...) 既存（先頭一致）→ index 追加不要。
ALTER TABLE performance_records DROP FOREIGN KEY fk_pr_user;

-- ===== residence / property ドメイン =====

-- 8. property_listings.listed_by → users (RESTRICT) クロスドメイン参照（出品者監査列）
--    撤廃only。検索は dwelling_unit/status 主導で listed_by 先頭の逆引きなし → index 追加不要。
ALTER TABLE property_listings DROP FOREIGN KEY fk_pl_listed_by;

-- 19. resident_documents.uploaded_by → users (RESTRICT) クロスドメイン参照（アップロード者監査列）
--     撤廃only。検索は resident 主導（idx_rd_resident）で uploaded_by 先頭の逆引きなし → index 追加不要。
ALTER TABLE resident_documents DROP FOREIGN KEY fk_rd_uploaded_by;

-- ===== proxy_input_consents（代理入力同意・users RESTRICT 5件。退会貫通の核心） =====

-- 9. proxy_input_consents.subject_user_id → users (RESTRICT) クロスドメイン参照（代理される本人）
--    撤廃only。idx_pic_subject (subject_user_id, ...) 既存（先頭一致）→ index 追加不要。
ALTER TABLE proxy_input_consents DROP FOREIGN KEY fk_pic_subject;

-- 10. proxy_input_consents.proxy_user_id → users (RESTRICT) クロスドメイン参照（代理者）
--     撤廃only。idx_pic_proxy (proxy_user_id, ...) 既存（先頭一致）→ index 追加不要。
ALTER TABLE proxy_input_consents DROP FOREIGN KEY fk_pic_proxy;

-- 11. proxy_input_consents.witness_user_id → users (RESTRICT) クロスドメイン参照（立会人）
--     撤廃only。立会人の前向き監査列で逆引き finder なし → index 追加不要。
ALTER TABLE proxy_input_consents DROP FOREIGN KEY fk_pic_witness;

-- 12. proxy_input_consents.approved_by_user_id → users (RESTRICT) クロスドメイン参照（承認管理者）
--     撤廃only。承認者の前向き監査列で逆引き finder なし → index 追加不要。
ALTER TABLE proxy_input_consents DROP FOREIGN KEY fk_pic_approved_by;

-- 13. proxy_input_consents.revoke_witnessed_by_user_id → users (RESTRICT) クロスドメイン参照（撤回立会ADMIN）
--     撤廃only。撤回立会人の前向き監査列で逆引き finder なし → index 追加不要。
ALTER TABLE proxy_input_consents DROP FOREIGN KEY fk_pic_revoke_wit;

-- ===== recruitment ドメイン =====

-- 14. recruitment_cancellation_policies.created_by → users (RESTRICT) クロスドメイン参照（作成者監査列）
--     撤廃only。検索は scope/template 主導で created_by 先頭の逆引きなし → index 追加不要。
ALTER TABLE recruitment_cancellation_policies DROP FOREIGN KEY fk_rcp_created_by;

-- 15. recruitment_listings.created_by → users (RESTRICT) クロスドメイン参照（作成者）
--     撤廃only。idx_rl_created_by (created_by) 既存（先頭一致）→ index 追加不要。
ALTER TABLE recruitment_listings DROP FOREIGN KEY fk_rl_created_by;

-- 16. recruitment_participants.user_id → users (RESTRICT) クロスドメイン参照（参加者本人）
--     撤廃only。idx_rp_user_status (user_id, ...) 既存（先頭一致）→ index 追加不要。
ALTER TABLE recruitment_participants DROP FOREIGN KEY fk_rp_user;

-- 17. recruitment_subcategories.created_by → users (RESTRICT) クロスドメイン参照（作成者監査列）
--     撤廃only。検索は scope 主導（idx_rs_scope）で created_by 先頭の逆引きなし → index 追加不要。
ALTER TABLE recruitment_subcategories DROP FOREIGN KEY fk_rs_created_by;

-- ===== reservation ドメイン =====

-- 18. reservations.user_id → users (RESTRICT) クロスドメイン参照（予約者本人）
--     撤廃only。idx_reservations_user_status_booked (user_id, ...) 既存（先頭一致）→ index 追加不要。
ALTER TABLE reservations DROP FOREIGN KEY fk_reservations_user;

-- ===== shift_budget ドメイン =====

-- 21. shift_budget_allocations.created_by → users (RESTRICT) クロスドメイン参照（作成者監査列）
--     撤廃only。検索は org/team/project/fiscal 主導で created_by 先頭の逆引きなし → index 追加不要。
ALTER TABLE shift_budget_allocations DROP FOREIGN KEY fk_sba_created_by;

-- 22. shift_budget_consumptions.user_id → users (RESTRICT) クロスドメイン参照（消化対象者）
--     撤廃only。idx_sbc_user_recorded (user_id, ...) 既存（先頭一致）→ index 追加不要。
ALTER TABLE shift_budget_consumptions DROP FOREIGN KEY fk_sbc_user;

-- ===== tag ドメイン =====

-- 23. tags.created_by → users (RESTRICT) クロスドメイン参照（作成者）
--     撤廃only。idx_tags_created_by (created_by) 既存（先頭一致）→ index 追加不要。
ALTER TABLE tags DROP FOREIGN KEY fk_tags_created_by;

-- ===== todo ドメイン =====

-- 24. todo_budget_links.created_by → users (RESTRICT) クロスドメイン参照（作成者監査列）
--     撤廃only。検索は project/todo/allocation 主導（idx_tbl_*）で created_by 先頭の逆引きなし → index 追加不要。
ALTER TABLE todo_budget_links DROP FOREIGN KEY fk_tbl_creator;

-- 25. todo_handoffs.from_user_id → users (RESTRICT) クロスドメイン参照（引き渡し元操作者）
--     撤廃only。idx_handoff_from_user (from_user_id, ...) 既存（先頭一致）→ index 追加不要。
ALTER TABLE todo_handoffs DROP FOREIGN KEY fk_handoff_from_user;
