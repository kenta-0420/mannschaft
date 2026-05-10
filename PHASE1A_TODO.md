# Phase 1-A クロスドメインFK 撤廃 — 残波 TODO

第一波（V62.001）で 9 件処理。第二波（V62.002）で 9 件処理。第三波（V62.003）で 12 件処理。第四波（V62.004）で 13 件処理。第五波（V62.005）で 11 件処理（organization_id 参照初波）。第六波（V62.006）で organization_id 残件 20 件処理（org_id 完全クローズ）。第七波（V62.007）で team_id 前半 35 件処理。第八波（V62.008）で team_id 後半 33 件処理（**team_id 完全クローズ**）。
残りは `users` 参照 多数（第九波以降）。

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

## 第九波以降（user_id 参照 残件）

主な候補（V2.x, V3.x, V10.x, V11.x 系列）：
- `schedules.fk_sch_user` / `schedules.fk_sch_created_by` (schedule)
- `report_actions.fk_report_actions_user` (admin)
- `moderation_appeals.fk_ma_user` (admin)
- `user_roles.fk_user_roles_user` / `fk_user_roles_granted_by` (auth/role)
- `team_org_memberships.fk_team_org_memberships_invited_by` 等 (team)
- `presence_events.fk_pe_user` (team)
- V11.x 系列（budget, skill, kb等）多数
- V18.x 系列（attendance等）多数

## 完了済み（team_id 参照 — 第七波・第八波）

### 第七波（V62.007）— 35件（team_id 前半）
- `chart_record_templates.fk_crt_team` (chart)
- `match_requests.fk_mr_team` (matching)
- `chart_section_settings.fk_css_team` (chart)
- `chart_intake_form_templates.fk_cift_team` (chart)
- `match_reviews.fk_mrev_reviewer` / `fk_mrev_reviewee` (matching)
- `match_proposals.fk_mp_proposing_team` / `fk_mp_cancelled_by` (matching)
- `equipment_items.fk_ei_team` (equipment)
- その他 chart/service/tournament/ticket/team_friends/ng_teams/payment/proxy/daily_attendance 各ドメイン 26 件
- ※ idx_mrev_reviewer_team / idx_mp_cancelled_by_team / idx_pvs_team_id を追加

### 第八波（V62.008）— 33件（team_id 後半・**完全クローズ**）
- `user_roles.fk_user_roles_team` (role)
- `permission_groups.fk_permission_groups_team` (role) ※ idx_permission_groups_team 追加
- `presence_events.fk_pe_team` (team)
- `team_org_memberships.fk_team_org_memberships_team` (team)
- `team_presence_icons.fk_tpi_team` (team)
- `coin_toss_results.fk_ctr_team` (team)
- `invite_tokens.fk_invite_tokens_team` (team) ※ idx_invite_tokens_team 追加
- `shopping_lists.fk_sl_team` (shopping)
- `audit_logs.fk_al_team` (audit)
- `personal_timetable_slots.fk_pts_linked_team` (timetable)
- `schedule_annual_copy_logs.fk_sacl_team` (schedule)
- `equipment_ranking_exclusions.fk_ere_team` (equipment)
- `team_role_aliases.fk_tra_team` (team)
- `period_attendance_records.fk_par_team` (attendance)
- `team_enabled_modules.fk_team_enabled_modules_team` (team)
- `attendance_transition_alerts.fk_ata_team` (attendance) ※ idx_ata_team 追加
- `blog_tags.fk_bt_team` (blog)
- `blog_post_shares.fk_blog_share_team` (blog)
- `team_officers.fk_team_officers_team` (team)
- `team_shift_settings.fk_team_shift_settings_team` (shift)
- `photo_albums.fk_pa_team` (photo)
- `team_member_info_responses.fk_tmir_team` (team_member)
- `member_profile_fields.fk_mpf_team` (team_member)
- `team_pages.fk_tp_team` (page)
- `team_custom_fields.fk_team_custom_fields_team` (content)
- `family_attendance_notices.fk_fan_team` (attendance)
- `team_anniversaries.fk_ta_team` (team)
- `team_blocks.fk_team_blocks_team` (team)
- `duty_rotations.fk_dr_team` (team)
- `shift_hourly_rates.fk_shr_team` (shift)
- `member_work_constraints.fk_mwc_team` (shift)
- `class_homerooms.fk_ch_team` (attendance)
- `member_availability_defaults.fk_mad_team` (shift)

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
