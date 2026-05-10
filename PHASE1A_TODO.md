# Phase 1-A クロスドメインFK 撤廃 — 残波 TODO

第一波（V62.001）で 9 件処理。第二波（V62.002）で 9 件処理。第三波（V62.003）で 12 件処理。第四波（V62.004）で 13 件処理。第五波（V62.005）で 11 件処理（organization_id 参照初波）。第六波（V62.006）で 20 件処理（organization_id 残件）。第七波（V62.007）で 35 件処理（team_id 前半）。第八波（V62.008）で 33 件処理（team_id 後半、team_id 完全クローズ）。第九波（V62.009）で 30 件処理（role/team/social/moderation/shift 系）。第十波（V62.010）で 30 件処理（workflow/activity/blog/form/property 系）。第十一波（V62.011）で 30 件処理（chat/timeline/bulletin/seal/shift 系）。
残りは `users` 参照 残件多数。

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

### 第六波（V62.006）— 20件（organization_id 残件）
- wave6 相当。第五波に続く org FK 撤廃。

### 第七波（V62.007）— 35件（team_id 前半）
- wave7 相当。team_id FK 撤廃前半。

### 第八波（V62.008）— 33件（team_id 後半）
- wave8 相当。team_id FK 撤廃後半。team_id 完全クローズ。

### 第九波（V62.009）— 30件（role/team/social/moderation/shift 系）
- wave9 相当。user_id FK 撤廃第一陣 30件。

### 第十波（V62.010）— 30件（workflow/activity/blog/form/property 系）
- wave10 相当。user_id FK 撤廃第二陣 30件。

### 第十一波（V62.011）— 30件（chat/timeline/bulletin/seal/shift 系）
- `activity_results.fk_ar_created_by` (activity)
- `photos.fk_ph_uploaded_by` (photo)
- `memberships.fk_memberships_invited_by`, `fk_memberships_user` (membership)
- `team_pages.fk_tp_created_by`, `member_profiles.fk_mp_user` (team)
- `chat_channels.fk_channel_creator`, `chat_channel_members.fk_member_user`, `chat_message_reactions.fk_reaction_user`, `chat_message_bookmarks.fk_bookmark_user`, `chat_messages.fk_msg_sender` (chat)
- `timeline_poll_votes.fk_poll_votes_user`, `timeline_posts.fk_timeline_posts_user`, `timeline_post_reactions.fk_post_reactions_user` (timeline)
- `content_reports.fk_content_reports_reviewer`, `user_mutes.fk_user_mutes_user` (moderation)
- `bulletin_reactions.fk_bulletin_reactions_user`, `bulletin_categories.fk_bulletin_categories_created_by`, `bulletin_attachments.fk_bulletin_attachments_created_by` (bulletin)
- `seal_stamp_logs.fk_seal_stamp_logs_user`, `seal_scope_defaults.fk_seal_scope_defaults_user`, `electronic_seals.fk_electronic_seals_user` (seal)
- `shift_swap_requests.fk_shift_swap_requests_target_user`, `fk_shift_swap_requests_claimed_by`, `shift_assignment_runs.fk_shift_assignment_runs_visual_review`, `shift_change_requests.fk_shift_change_requests_requested_by`, `fk_shift_change_requests_reviewer` (shift)
- `survey_responses.fk_survey_responses_user`, `survey_result_viewers.fk_survey_result_viewers_user` (survey)
- `shared_files.fk_shared_files_created` (storage)

## 第十二波以降（user_id 参照 残件）

主な候補（V2.x, V3.x, V10.x, V11.x 系列）：
- `schedules.fk_sch_user` / `schedules.fk_sch_created_by` (schedule)
- `report_actions.fk_report_actions_user` (admin)
- `moderation_appeals.fk_ma_user` (admin)
- `user_roles.fk_user_roles_user` / `fk_user_roles_granted_by` (auth/role)
- `team_org_memberships.fk_team_org_memberships_invited_by` 等 (team)
- `presence_events.fk_pe_user` (team)
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
