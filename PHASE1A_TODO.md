# Phase 1-A クロスドメインFK 撤廃 — 残波 TODO

第一波（V62.001）で 9 件処理。第二波（V62.002）で 9 件処理。第三波（V62.003）で 12 件処理。第四波（V62.004）で 13 件処理。第五波（V62.005）で 11 件処理（organization_id 参照初波）。第九波（V62.009）で 30 件処理（role/team/social/moderation/shift ドメインの user_id 参照）。
残りは `users` 参照 残件 + `teams` 多数 + `organizations` 残件 + その他多数。

## 完了済み（user_id 参照）

### 第一波（V62.001）— 9件
- `point_transactions`, `user_badges`, `ranking_snapshots`, `gamification_user_settings` (gamification)
- `contact_requests`(×2), `contact_request_blocks`(×2), `contact_invite_tokens` (contact)

### 第二波（V62.002）— 9件
- `feedback_votes`, `user_violations` (admin)
- `onboarding_progresses` (onboarding)
- `data_exports` (data)
- `personal_timetables` (timetable)
- `offline_sync_conflicts` (sync)
- `user_blocks`(×2) (social)
- `todo_personal_memos` (todo)

### 第三波（V62.003）— 7件（user_id）
- `kb_page_favorites.fk_kbpf_user` (knowledge)
- `user_google_calendar_connections.fk_ugcc_user` (calendar)
- `user_calendar_sync_settings.fk_ucss_user` (calendar)
- `user_ical_tokens.fk_uit_user` (calendar)
- `announcement_read_status.fk_ars_user` (announcement)
- `event_rsvp_responses.fk_rsvp_user` (event) ※ idx_rsvp_user_id 追加
- `personal_timetable_settings.fk_pts_settings_user` (timetable)

## 完了済み（team_id 参照）

### 第三波（V62.003）— 5件（team_id 第一波）
- `chat_channels.fk_channel_team` (chat)
- `timetable_terms.fk_tt_team` (timetable)
- `personal_timetable_share_targets.fk_ptst_team` (timetable)
- `schedule_event_categories.fk_sec_team` (schedule)
- `job_postings.fk_jp_team` (jobmatching)

### 第四波（V62.004）— 13件（user_id 6件 + team_id 7件）

#### user_id 参照（6件）
- `safety_responses.fk_safety_resp_user` (safety) ※ idx_safety_resp_user_id 追加
- `schedule_attendances.fk_sa_user` (schedule)
- `shift_requests.fk_sr_user` (shift) ※ idx_shift_requests_user_id 追加
- `member_cards.fk_mc_user` (member)
- `visibility_templates.fk_vt_owner` (visibility)
- `confirmable_notification_recipients.fk_cnr_user` (notification)

#### team_id 参照（7件）
- `timetables.fk_tm_team` (timetable → team)
- `schedules.fk_sch_team` (schedule → team)
- `shift_schedules.fk_ss_team` (shift → team)
- `shift_positions.fk_sp_team` (shift → team)
- `shared_folders.fk_shared_folders_team` (storage → team) ※ idx_shared_folders_team_id 追加
- `blog_posts.fk_bp_team` (blog → team)
- `blog_post_series.fk_bps_team` (blog → team)

## 完了済み（user_id 参照）— 第九波

### 第九波（V62.009）— 30件（role/team/social/moderation/shift 系）

#### role ドメイン（5件）
- `user_roles.fk_user_roles_user` ※ UNIQUE(user_id, scope_key) でカバー済み
- `user_roles.fk_user_roles_granted_by` ※ idx_user_roles_granted_by 追加
- `user_permission_groups.fk_upg_user` ※ idx_upg_user_id 追加
- `user_permission_groups.fk_upg_assigned_by` ※ idx_upg_assigned_by 追加
- `permission_groups.fk_permission_groups_created_by` ※ idx_permission_groups_created_by 追加

#### team ドメイン（13件）
- `team_org_memberships.fk_team_org_memberships_invited_by` ※ idx_team_org_memberships_invited_by 追加
- `team_org_memberships.fk_team_org_memberships_responded_by` ※ idx_team_org_memberships_responded_by 追加
- `invite_tokens.fk_invite_tokens_created_by` ※ idx_invite_tokens_created_by 追加
- `presence_events.fk_pe_user` ※ idx_presence_events_user_id 追加
- `team_presence_icons.fk_tpi_user` ※ idx_tpi_updated_by 追加
- `organization_blocks.fk_org_blocks_user` ※ idx_org_blocks_user_id 追加
- `organization_blocks.fk_org_blocks_blocked_by` ※ idx_org_blocks_blocked_by 追加
- `team_blocks.fk_team_blocks_user` ※ idx_team_blocks_user_id 追加
- `team_blocks.fk_team_blocks_blocked_by` ※ idx_team_blocks_blocked_by 追加
- `team_anniversaries.fk_ta_user` ※ idx_ta_created_by 追加
- `duty_rotations.fk_dr_user` ※ idx_dr_created_by 追加
- `coin_toss_results.fk_ctr_user` ※ idx_ctr_user_id 追加
- `team_role_aliases.fk_tra_user` ※ idx_tra_updated_by 追加

#### care ドメイン（4件）
- `user_care_links.fk_ucl_recipient` ※ idx_ucl_recipient_status でカバー済み
- `user_care_links.fk_ucl_watcher` ※ idx_ucl_watcher_status でカバー済み
- `user_care_links.fk_ucl_created_by` ※ idx_ucl_created_by 追加
- `user_care_links.fk_ucl_revoked_by` ※ idx_ucl_revoked_by 追加

#### social ドメイン（1件）
- `user_social_profiles.fk_social_profiles_user` ※ idx_social_profiles_user_id 追加

#### moderation ドメイン（4件）
- `report_actions.fk_report_actions_user` ※ idx_report_actions_action_by 追加
- `moderation_appeals.fk_ma_user` ※ UNIQUE(user_id, action_id) でカバー済み
- `report_internal_notes.fk_rin_author` ※ idx_rin_author_id 追加
- `moderation_settings_history.fk_msh_user` ※ idx_msh_changed_by 追加

#### shift ドメイン（3件）
- `member_work_constraints.fk_mwc_user` ※ idx_mwc_user_id 追加
- `member_availability_defaults.fk_mad_user` ※ UNIQUE(user_id, ...) でカバー済み
- `shift_hourly_rates.fk_shr_user` ※ UNIQUE(user_id, ...) でカバー済み

## 第十波以降（user_id 参照 残件）

主な候補（V2.x, V3.x, V11.x 系列）：
- `schedules.fk_sch_user` / `schedules.fk_sch_created_by` (schedule)
- V11.x 系列（budget, skill, kb等）多数
- V18.x 系列（attendance等）多数

## 第五波以降（team_id 参照 残件）

主な候補：
- `reservation_lines/slots/reservations` (reservation → team)
- `match_requests`, `match_proposals` (matching → team)
- `service_records`, `performance_metrics` (service → team)
- `equipment_items` (equipment → team)
- `payment_items`, `ticket_products` 等 (payment → team)
- その他多数

## 完了済み（organization_id 参照）

### 第五波（V62.005）— 11件（organization_id 第一波）
- `chat_channels.fk_channel_org` (chat)
- `timetable_period_templates.fk_tpt_organization` (timetable)
- `timetable_terms.fk_timetable_term_org` (timetable)
- `schedule_event_categories.fk_sec_organization` (schedule)
- `shift_budget_allocations.fk_sba_organization` (shift)
- `shift_budget_failed_events.fk_sbfe_organization` (shift)
- `ad_campaigns.fk_ad_campaigns_org` (advertising)
- `advertiser_accounts.fk_advertiser_accounts_organization` (advertising) ※idx_advertiser_accounts_org 追加
- `proxy_input_consents.fk_pic_org` (proxy)
- `user_roles.fk_user_roles_org` (role)
- `permission_groups.fk_permission_groups_org` (role) ※idx_permission_groups_org 追加

## 次波（organization_id 参照 残件 — 第六波以降）

- `schedule_annual_copy_logs.fk_sacl_organization` (schedule → organization, ON DELETE SET NULL)
- `audit_logs.fk_al_org` (audit → organization, ON DELETE SET NULL)
- `error_reports` (error → organization, ON DELETE SET NULL)
- `action_memos` org FK (memo → organization)
- `organizations.fk_organizations_parent` — **同一ドメイン内のため撤廃不要**
- V8.x / V9.x 系列（committees, proxy_vote_sessions, payment_items など）

## Phase 1-B（CASCADE 整理）

CLAUDE.md 原則 §2 に従い、クロスドメイン CASCADE 78 件は
`SET NULL` / `RESTRICT` / アプリ層整合性に置換。
※ Phase 1-A の FK 撤廃で大半の CASCADE は同時消滅するため、
   Phase 1-A 完了後に再棚卸しが必要。

## 参考

- 全体陣立て: `~/.claude/projects/C--Claude-mannschaft/memory/project_db_scalability_10m_users.md`
- 設計原則: `CLAUDE.md` L273-349
