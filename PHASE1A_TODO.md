# Phase 1-A クロスドメインFK 撤廃 — 残波 TODO

第一波（V62.001）で 9 件処理。第二波（V62.002）で 9 件処理。第三波（V62.003）で 12 件処理。第四波（V62.004）で 13 件処理。第五波（V62.005）で 11 件処理（organization_id 参照初波）。第六波（V62.006）で 20 件処理（organization_id 参照 残件全件 — **org_id 完全クローズ**）。第七波（V62.007）で 35 件処理（team_id 前半）。第八波（V62.008）で 33 件処理（team_id 後半 — **team_id 完全クローズ**）。第九波（V62.009）で 30 件処理（user_id 第一陣）。第十波（V62.010）で 30 件処理（user_id 第二陣）。第十一波（V62.011）で 30 件処理（user_id 第三陣）。第十二波（V62.012）で 30 件処理（user_id 第四陣）。第十三波（V62.013）で 30 件処理（user_id 第五陣 — committee/facility/chart/service 系）。
残りは `users` 参照 多数 + その他多数。

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

### 第十三波（V62.013）— 30件（user_id 第五陣）
- `committee_invitations.fk_ci_invitee` (committee) ※idx_committee_invitations_invitee 既存
- `committee_invitations.fk_ci_invited_by` (committee) ※idx_committee_invitations_invited_by 追加
- `committees.fk_committees_created_by` (committee) ※idx_committees_created_by 追加
- `blog_post_reactions.fk_bpreact_user` (blog) ※idx_bpreact_user 既存
- `my_scope_folders.fk_msf_user` (scoping) ※idx_msf_user_scope 既存
- `facility_bookings.fk_fb_booked_by` (facility) ※idx_fb_user 既存
- `facility_bookings.fk_fb_created_by_admin` (facility) ※idx_fb_created_by_admin 追加
- `facility_bookings.fk_fb_approved_by` (facility) ※idx_fb_approved_by 追加
- `facility_bookings.fk_fb_cancelled_by` (facility) ※idx_fb_cancelled_by 追加
- `facility_booking_payments.fk_fbp_payer` (facility) ※idx_fbp_payer 既存
- `shared_facilities.fk_sf_created_by` (facility) ※idx_sf_created_by 追加
- `storage_usage_logs.fk_sul_actor` (storage) ※idx_sul_actor 追加
- `team_friend_folder_members.fk_tffm_added_by` (social) ※idx_tffm_added_by 既存
- `multipart_upload_sessions.fk_mup_uploader` (storage) ※idx_mup_uploader 既存
- `performance_monthly_summaries.fk_pms_user` (performance) ※idx_pms_user_month 既存
- `chart_records.fk_cr_customer` (service) ※idx_cr_customer 既存
- `chart_records.fk_cr_staff` (service) ※idx_cr_staff 既存
- `equipment_assignments.fk_ea_assigned_to` (equipment) ※idx_ea_user 既存
- `equipment_assignments.fk_ea_assigned_by` (equipment) ※idx_ea_assigned_by 追加
- `equipment_assignments.fk_ea_returned_by` (equipment) ※idx_ea_returned_by 追加
- `member_payments.fk_member_pay_user` (payment) ※idx_mp_user_item 既存
- `member_payments.fk_mp_recorded_by` (payment) ※idx_mp_recorded_by 追加
- `stripe_customers.fk_sc_user` (payment) ※uq_sc_user UNIQUE 既存
- `payment_items.fk_pi_created_by` (payment) ※idx_pi_created_by 追加
- `service_records.fk_sr_member` (service) ※idx_sr_member 既存
- `service_records.fk_sr_staff` (service) ※idx_sr_staff 追加
- `property_work_packages.fk_pwp_updated_by` (property) ※idx_pwp_updated_by 追加
- `team_member_info_responses.fk_tmir_user` (team) ※uq_tmir_user_field UNIQUE 既存
- `service_record_reactions.fk_srr_user` (service) ※uq_srr_record_user UNIQUE 既存
- `performance_records.fk_pr_recorded_by` (performance) ※idx_pr_recorded_by 追加

## 第五波以降（user_id 参照 残件）

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
