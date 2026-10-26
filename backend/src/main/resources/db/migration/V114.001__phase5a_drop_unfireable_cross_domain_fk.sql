-- Phase 5-A（最終局面・第一弾）: クロスドメインFK撤廃 — 「発火不能群」12件を撤廃only（孤児保持）。
-- CASCADE 7件＋RESTRICT 5件（→ organizations 4件 / → teams 1件）を撤廃する。
--
-- 1000万ユーザー耐久DB再構築 クロスドメインFK撤廃キャンペーン Phase 5-A。
-- CLAUDE.md §1「クロスドメインFKは作らない」/ §2「CASCADE/SET NULL のクロスドメイン削除連鎖は禁止」
-- 原則に従い、クロスドメイン（別ドメインの実テーブル）を参照する FK を撤廃する。
-- 第一〜四陣で「user/team/org 親の CASCADE」「群1=users 親 SET NULL」「群2=他テーブル SET NULL」を全廃済。
-- 本陣（最終局面 5-A）が対象とするのは「残った RESTRICT 43＋CASCADE 7」のうち、参照先が物理削除されず
-- CASCADE/RESTRICT が現実には発火し得ない「発火不能群」12件である。
--
-- ━━━ なぜ「撤廃only・孤児保持・リスナー/データ操作/NULL化なし」が無条件に安全なのか ━━━
-- 本 PR-5a の12件は、参照先テーブルがいずれも「論理削除（@SQLRestriction / deleted_at）のみで物理削除されない」:
--   ・timeline_posts   : deleted_at による論理削除のみ（物理 DELETE 経路なし）。
--   ・shared_files     : deleted_at による論理削除のみ。
--   ・committees       : deleted_at による論理削除のみ（コアに準ずる委員会マスタ）。
--   ・projects         : deleted_at による論理削除のみ。
--   ・todos            : deleted_at による論理削除のみ。
--   ・organizations    : コアエンティティ。deleted_at による論理削除（CLAUDE.md 原則3）。物理削除されない。
--   ・teams            : コアエンティティ。deleted_at による論理削除（CLAUDE.md 原則3）。物理削除されない。
-- 参照先の行が物理削除される契機が存在しない以上、ON DELETE CASCADE も ON DELETE RESTRICT（既定）も
-- 発火し得ない＝挙動を一切変えずに FK だけを撤廃できる（第一陣の team/org 親 CASCADE 撤廃と同じ論理）。
-- 撤廃後に万一参照先が物理削除されても、子の外部キー列は孤児値を保持する（CASCADE 連鎖削除も RESTRICT 阻止も起きない）。
-- 参照整合性は今後アプリ層で保証する（CLAUDE.md §1）。
--
-- 本 migration は「純粋な FK 撤廃のみ」である。リスナー・データ操作・NULL 化処理は一切伴わない。
-- 番人テスト（FlywayExistingDataUnfireableCrossDomainFkMigrationTest）が守る不変条件は
-- 「参照先テーブルの行を（テスト内で意図的に）物理 DELETE しても、参照元の外部キー列が削除/NULL化されず
--  孤児値を保持し続ける」ことであり、CASCADE/RESTRICT 撤廃only の肝を直接検証する。
--
-- ━━━ 対象一覧（12件・すべて明示名）━━━
-- CASCADE 7件:
--  1. friend_content_forwards    / fk_fcf_forwarded_post (forwarded_post_id → timeline_posts CASCADE)
--  2. friend_content_forwards    / fk_fcf_source_post    (source_post_id    → timeline_posts CASCADE)
--  3. property_work_documents    / fk_pwd_file           (shared_file_id    → shared_files   CASCADE)
--  4. schedules                  / fk_schedules_committee(committee_id      → committees     CASCADE)
--  5. todo_budget_links          / fk_tbl_project        (project_id        → projects       CASCADE)
--  6. todo_budget_links          / fk_tbl_todo           (todo_id           → todos          CASCADE)
--  7. todo_tag_links             / fk_todo_tag_links_todo(todo_id           → todos          CASCADE)
-- RESTRICT → organizations 4件（既定 RESTRICT）:
--  8. notification_credit_purchases     / fk_ncp_org (organization_id → organizations)
--  9. notification_monthly_usage        / fk_nmu_org (organization_id → organizations)
-- 10. organization_enabled_modules      / fk_oem_org (organization_id → organizations)
-- 11. organization_notification_balances/ fk_onb_org (organization_id → organizations)
-- RESTRICT → teams 1件:
-- 12. recruitment_participants          / fk_rp_team (team_id → teams)
--
-- ━━━ index 判定（FK 撤廃で暗黙バッキング index が消えても CREATE INDEX 追加が必要か）━━━
-- 結論: 12件とも CREATE INDEX 追加は不要。各撤廃対象 FK 列を「先頭」に持つ非FK index（または UNIQUE）が既存:
--  1. friend_content_forwards.forwarded_post_id : idx_fcf_forwarded_post 等の専用 index は無いが、forwarded_post_id は
--       転送先投稿への前向き参照（write-only / 逆引き finder なし・冷たい関連列）→ index 追加不要。
--  2. friend_content_forwards.source_post_id     : INDEX idx_fcf_source_post (source_post_id) 既存（先頭一致）→ 追加不要。
--  3. property_work_documents.shared_file_id      : INDEX idx_pwd_file (shared_file_id) 既存（先頭一致）→ 追加不要。
--  4. schedules.committee_id                      : INDEX idx_schedules_committee (committee_id, start_at) 既存（先頭一致）→ 追加不要。
--  5. todo_budget_links.project_id                : INDEX idx_tbl_project (project_id) 既存（先頭一致）→ 追加不要。
--  6. todo_budget_links.todo_id                   : INDEX idx_tbl_todo (todo_id) 既存（先頭一致）→ 追加不要。
--  7. todo_tag_links.todo_id                      : UNIQUE KEY uq_todo_tag_links (todo_id, tag_id) 既存（先頭一致）→ 追加不要。
--  8. notification_credit_purchases.organization_id : INDEX idx_ncp_org (organization_id, payment_status) 既存（先頭一致）→ 追加不要。
--  9. notification_monthly_usage.organization_id    : UNIQUE KEY uq_nmu (organization_id, month, source_type) 既存（先頭一致）→ 追加不要。
-- 10. organization_enabled_modules.organization_id  : INDEX idx_org_enabled (organization_id, is_enabled) ＋ UNIQUE uq_org_module 既存（先頭一致）→ 追加不要。
-- 11. organization_notification_balances.organization_id : UNIQUE KEY（organization_id・1組織1行）既存（先頭一致）→ 追加不要。
-- 12. recruitment_participants.team_id              : INDEX idx_rp_team_status (team_id, status, applied_at) 既存（先頭一致）→ 追加不要。
--
-- ━━━ 対象外（本 migration では一切触らない）━━━
--   ・friend_content_forwards     → teams（fk_fcf_source_team / fk_fcf_forwarding_team CASCADE）/ → users（fk_fcf_forwarded_by RESTRICT / fk_fcf_revoked_by SET NULL）。
--   ・property_work_documents      → property_work_packages（fk_pwd_package CASCADE・同一 property ドメイン）/ → users（fk_pwd_created_by RESTRICT）。
--   ・schedules                    → teams/organizations/users（fk_sch_team/fk_sch_org/fk_sch_user CASCADE）/ → users（fk_sch_created_by SET NULL）/ → schedules（fk_sch_parent RESTRICT・同一ドメイン）/ → venues（fk_sch_venue RESTRICT・別軍）/ → todos（fk_schedules_todos SET NULL・別軍）。
--   ・todo_budget_links            → shift_budget_allocations（fk_tbl_allocation RESTRICT・別軍）/ → users（fk_tbl_creator RESTRICT）。
--   ・todo_tag_links               → tags（fk_todo_tag_links_tag CASCADE・別軍）。
--   ・notification_credit_purchases→ notification_credit_packages（fk_ncp_pkg・同一 notification ドメインのマスタ）。
--   ・organization_enabled_modules → module_definitions（fk_oem_module RESTRICT・template ドメイン許容）/ → users（fk_oem_user SET NULL）。
--   ・recruitment_participants     → recruitment_listings（fk_rp_listing CASCADE・同一ドメイン）/ → users（fk_rp_user RESTRICT / fk_rp_applied_by / fk_rp_cancelled_by SET NULL）。
--   上記12の「→ timeline_posts / shared_files / committees / projects / todos / organizations / teams」のみを DROP する。

-- ===== CASCADE 7件 =====

-- 1. friend_content_forwards.forwarded_post_id → timeline_posts (CASCADE) クロスドメイン構造参照
--    撤廃only。timeline_posts は論理削除のみ（物理削除なし）で CASCADE 発火不能。冷たい関連列・逆引き finder なし → index 追加不要。
ALTER TABLE friend_content_forwards DROP FOREIGN KEY fk_fcf_forwarded_post;

-- 2. friend_content_forwards.source_post_id → timeline_posts (CASCADE) クロスドメイン構造参照
--    撤廃only。timeline_posts は論理削除のみで CASCADE 発火不能。idx_fcf_source_post (先頭=当該列) 既存 → index 追加不要。
ALTER TABLE friend_content_forwards DROP FOREIGN KEY fk_fcf_source_post;

-- 3. property_work_documents.shared_file_id → shared_files (CASCADE) クロスドメイン構造参照
--    撤廃only。shared_files は論理削除（deleted_at）のみで CASCADE 発火不能。idx_pwd_file (先頭=当該列) 既存 → index 追加不要。
ALTER TABLE property_work_documents DROP FOREIGN KEY fk_pwd_file;

-- 4. schedules.committee_id → committees (CASCADE) クロスドメイン構造参照
--    撤廃only。committees は論理削除（deleted_at）のみで CASCADE 発火不能。idx_schedules_committee (先頭=当該列) 既存 → index 追加不要。
ALTER TABLE schedules DROP FOREIGN KEY fk_schedules_committee;

-- 5. todo_budget_links.project_id → projects (CASCADE) クロスドメイン構造参照
--    撤廃only。projects は論理削除（deleted_at）のみで CASCADE 発火不能。idx_tbl_project (先頭=当該列) 既存 → index 追加不要。
ALTER TABLE todo_budget_links DROP FOREIGN KEY fk_tbl_project;

-- 6. todo_budget_links.todo_id → todos (CASCADE) クロスドメイン構造参照
--    撤廃only。todos は論理削除（deleted_at）のみで CASCADE 発火不能。idx_tbl_todo (先頭=当該列) 既存 → index 追加不要。
ALTER TABLE todo_budget_links DROP FOREIGN KEY fk_tbl_todo;

-- 7. todo_tag_links.todo_id → todos (CASCADE) クロスドメイン構造参照
--    撤廃only。todos は論理削除のみで CASCADE 発火不能。UNIQUE uq_todo_tag_links (todo_id, tag_id) (先頭=当該列) 既存 → index 追加不要。
ALTER TABLE todo_tag_links DROP FOREIGN KEY fk_todo_tag_links_todo;

-- ===== RESTRICT → organizations 4件（organizations はコアエンティティ・論理削除のみ＝RESTRICT 発火不能） =====

-- 8. notification_credit_purchases.organization_id → organizations (RESTRICT) クロスドメイン参照
--    撤廃only。organizations は論理削除（deleted_at）のみで物理削除されず RESTRICT 発火不能。idx_ncp_org (先頭=当該列) 既存 → index 追加不要。
ALTER TABLE notification_credit_purchases DROP FOREIGN KEY fk_ncp_org;

-- 9. notification_monthly_usage.organization_id → organizations (RESTRICT) クロスドメイン参照
--    撤廃only。organizations は論理削除のみで RESTRICT 発火不能。UNIQUE uq_nmu (先頭=当該列) 既存 → index 追加不要。
ALTER TABLE notification_monthly_usage DROP FOREIGN KEY fk_nmu_org;

-- 10. organization_enabled_modules.organization_id → organizations (RESTRICT) クロスドメイン参照
--    撤廃only。organizations は論理削除のみで RESTRICT 発火不能。idx_org_enabled / uq_org_module (先頭=当該列) 既存 → index 追加不要。
ALTER TABLE organization_enabled_modules DROP FOREIGN KEY fk_oem_org;

-- 11. organization_notification_balances.organization_id → organizations (RESTRICT) クロスドメイン参照
--    撤廃only。organizations は論理削除のみで RESTRICT 発火不能。UNIQUE（organization_id・1組織1行）既存 → index 追加不要。
ALTER TABLE organization_notification_balances DROP FOREIGN KEY fk_onb_org;

-- ===== RESTRICT → teams 1件（teams はコアエンティティ・論理削除のみ＝RESTRICT 発火不能） =====

-- 12. recruitment_participants.team_id → teams (RESTRICT) クロスドメイン参照
--    撤廃only。teams は論理削除（deleted_at）のみで物理削除されず RESTRICT 発火不能。idx_rp_team_status (先頭=当該列) 既存 → index 追加不要。
ALTER TABLE recruitment_participants DROP FOREIGN KEY fk_rp_team;
