# Phase 1-A クロスドメインFK 撤廃 — 残波 TODO

第一波（V62.001）で 9 件処理。第二波（V62.002）で 9 件処理。第三波（V62.003）で 12 件処理。第四波（V62.004）で 13 件処理。第五波（V62.005）で 11 件処理（organization_id 参照初波）。第六波（V62.006）で 20 件処理（organization_id 参照 残件全件 — **org_id 完全クローズ**）。第七波（V62.007）で 35 件処理（team_id 参照前半）。第八波（V62.008）で 33 件処理（team_id 参照後半 — **team_id 完全クローズ**）。第九波（V62.009）で 30 件処理（user_id 参照 role/team/social/moderation/shift 系）。第十波（V62.010）で 30 件処理（user_id 参照 workflow/activity/blog/form 系）。第十一波（V62.011）で 30 件処理（user_id 参照 chat/timeline/bulletin/seal/shift 系）。第十二波（V62.012）で 30 件処理（user_id 参照 circulation/bulletin/mentions/corkboard/tournament/committee 系）。**第十四波（V62.014）で 30 件処理（user_id 参照 receipt/proxy_vote/signage/incident 系）（本陣）**。
残りは `users` 参照 多数 + その他。

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

## 完了済み（team_id 参照 追加分）

### 第七波（V62.007）— 35件（team_id 参照前半）
- `contact_request_team_links`, `match_requests`, `match_proposals`, `coach_scout_invitation_follows`, `match_proposal_reviewers` (matching)
- `service_records`, `service_record_forms`, `service_record_form_templates`, `performance_metrics` (service)
- `equipment_items` (equipment)
- `shopping_lists` (shopping)
- `ticket_books`, `ticket_products`, `ticket_payments`, `payment_items` (payment/ticket)
- `team_friendships`(×2), `neighborhood_groups`(×2), `friend_content_forwards`(×2), `team_friend_links` (social/friend)
- `team_access_requirements`, `member_role_transitions`, `monthly_net_payments`, `proxy_vote_sessions` (misc)
- `daily_attendance_records`, `custom_schedule_items`, `curriculum_item_follows` (schedule/attendance)

### 第八波（V62.008）— 33件（team_id 参照後半 — **team_id 完全クローズ**）
- `user_roles`, `permission_groups` (role)
- `presence_events`, `team_org_memberships`, `team_presence_icons`, `coin_toss_results`, `invite_tokens`, `team_role_aliases`, `team_enabled_modules`, `team_officers`, `team_shift_settings`, `team_anniversaries`, `team_blocks`, `duty_rotations` (team)
- `audit_logs.fk_al_team` (audit)
- `personal_timetable_slots.fk_pts_linked_team` (timetable)
- `schedule_annual_copy_logs.fk_sacl_team` (schedule)
- `equipment_ranking_exclusions` (equipment)
- `period_attendance_records`, `attendance_transition_alerts`, `class_homerooms`, `family_attendance_notices` (attendance)
- `blog_tags.fk_bt_team`, `blog_post_shares.fk_blog_share_team` (blog)
- `photo_albums.fk_pa_team` (photo)
- `team_member_info_responses`, `member_profile_fields.fk_mpf_team` (member)

## 完了済み（user_id 参照 追加分）

### 第九波（V62.009）— 30件
- `user_roles`(×2), `user_permission_groups`(×2), `permission_groups.fk_permission_groups_created_by` (role)
- `team_org_memberships`(×2), `invite_tokens.fk_invite_tokens_created_by`, `presence_events.fk_pe_user`, `team_presence_icons`, `organization_blocks`(×2), `team_blocks`(×2), `team_anniversaries`, `duty_rotations`, `coin_toss_results`, `team_role_aliases` (team)
- `user_care_links`(×4) (care)
- `user_social_profiles` (social)
- `report_actions`, `moderation_appeals`, `report_internal_notes`, `moderation_settings_history` (moderation)
- `member_work_constraints`, `member_availability_defaults`, `shift_hourly_rates` (shift)

### 第十波（V62.010）— 30件
- `activity_templates`, `activity_participants`, `activity_comments` (activity)
- `workflow_request_comments`, `workflow_request_attachments`, `workflow_requests`, `workflow_request_approvers`, `workflow_templates` (workflow)
- `blog_post_revisions`, `blog_post_shares.fk_bps_shared_by`, `user_blog_settings`, `blog_image_uploads` (blog)
- `survey_targets`, `form_submissions`, `form_templates`, `system_form_presets` (form)
- `shared_file_stars`, `shared_file_links`, `shared_file_comments`, `shared_file_tags` (storage)
- `member_positions` (membership)
- `property_work_history_views`, `disclosure_form_drafts`(×2), `photo_albums.fk_pa_created_by`, `disclosure_exports`, `disclosure_form_templates`, `property_work_packages`, `vendors`, `property_work_documents` (property/disclosure)

### 第十一波（V62.011）— 30件
- chat/timeline/bulletin/seal/shift 系（詳細は V62.011 参照）
- `shift_assignment_runs.fk_shift_assignment_runs_visual_review` 含む

### 第十二波（V62.012）— 30件
- `circulation_recipients`, `circulation_documents`, `circulation_comments` (circulation)
- `bulletin_replies`, `bulletin_read_status`, `bulletin_threads` (bulletin)
- `mentions`(×2) (mention)
- `corkboards`, `corkboard_cards` (corkboard)
- `sns_feed_configs` (sns)
- `tournament_promotion_records`, `tournament_templates`, `tournaments`, `tournament_individual_rankings`, `tournament_match_rosters`, `tournament_match_player_stats` (tournament)
- `user_line_connections`, `line_bot_configs` (line)
- `direct_mail_templates`, `direct_mail_image_uploads`, `direct_mail_logs`, `direct_mail_recipients` (direct_mail)
- `ticket_consumptions`(×2), `ticket_books.fk_tb_issued_by` (ticket)
- `committee_members`(×2) (committee)
- `friend_content_forwards`(×2) (social)

### 第十四波（V62.014）— 30件（本陣）
- `service_record_templates.fk_srt_user` (service_record)
- `receipts`(×3), `receipt_queue`, `receipt_presets`, `receipt_issuer_settings` (receipt)
- `ticket_products`, `ticket_payments`(×2), `ticket_books.fk_tb_user` (ticket)
- `proxy_vote_motion_comments`, `proxy_vote_attachments`, `content_payment_gates`, `proxy_vote_sessions`, `proxy_votes`, `proxy_delegations`(×3) (proxy_vote)
- `shift_assignment_runs.fk_shift_assignment_runs_triggered_by` (shift)
- `audit_logs`(×2): `fk_al_user`, `fk_al_target_user` (audit)
- `signage_emergency_messages`(×2), `signage_screens` (signage)
- `incoming_webhook_tokens` (webhook)
- `error_report_ai_analyses`, `error_report_activities` (error_report)
- `confirmable_notification_templates` (confirmable_notification)
- `incident_assignments` (incident)

## 第十五波以降（user_id 参照 残件）

主な候補（V2.x, V3.x, V10.x, V11.x 系列）：
- `schedules.fk_sch_user` / `schedules.fk_sch_created_by` (schedule)
- V11.x 系列（budget, skill, kb等）多数
- V18.x 系列（attendance等）多数

## team_id 参照 — 完全クローズ（V62.003/004/007/008 で全件撤廃済）

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

### 第六波（V62.006）— 20件（organization_id 残件全件 — **org_id 完全クローズ**）
- `equipment_items.fk_ei_org` (equipment)
- `payment_items.fk_pi_organization` (payment)
- `service_record_templates.fk_srt_org` (service)
- `team_pages.fk_tp_organization` (team)
- `team_org_memberships.fk_team_org_memberships_org` (team) ※idx_team_org_memberships_org 追加
- `photo_albums.fk_pa_organization` (photo)
- `tournaments.fk_t_organization` (tournament)
- `tournament_templates.fk_tt_organization` (tournament)
- `committees.fk_committees_org` (committee)
- `proxy_vote_sessions.fk_pvs_organization` (proxy)
- `organization_access_requirements.fk_oar_organization` (payment)
- `invite_tokens.fk_invite_tokens_org` (auth) ※idx_invite_tokens_org 追加
- `organization_blocks.fk_org_blocks_org` (social)
- `schedule_annual_copy_logs.fk_sacl_organization` (schedule)
- `audit_logs.fk_al_org` (audit)
- `blog_post_shares.fk_blog_share_org` (blog)
- `blog_tags.fk_bt_org` (blog)
- `member_profile_fields.fk_mpf_organization` (profile)
- `organization_custom_fields.fk_org_custom_fields_org` (organization)
- `organization_officers.fk_org_officers_org` (organization)

**除外**: `organizations.fk_organizations_parent` は同一ドメイン内（自己参照）のため撤廃しない

## organization_id 参照 — 完全クローズ（V62.005 + V62.006 で全件撤廃済）

## Phase 1-B（CASCADE 整理）

CLAUDE.md 原則 §2 に従い、クロスドメイン CASCADE 78 件は
`SET NULL` / `RESTRICT` / アプリ層整合性に置換。
※ Phase 1-A の FK 撤廃で大半の CASCADE は同時消滅するため、
   Phase 1-A 完了後に再棚卸しが必要。

## 参考

- 全体陣立て: `~/.claude/projects/C--Claude-mannschaft/memory/project_db_scalability_10m_users.md`
- 設計原則: `CLAUDE.md` L273-349
