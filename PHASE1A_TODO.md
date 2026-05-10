# Phase 1-A クロスドメインFK 撤廃 — 残波 TODO

第一波（V62.001）で 9 件処理。第二波（V62.002）で 9 件処理。第三波（V62.003）で 12 件処理。第四波（V62.004）で 13 件処理。第五波（V62.005）で 11 件処理（organization_id 参照初波）。第六波（V62.006）で 20 件処理（org_id 完全クローズ）。第七波（V62.007）で 35 件処理（team_id 参照 前半）。
残りは `users` 参照 多数 + `teams` 残件 + その他多数。

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

## 完了済み（team_id 参照 — 続き）

### 第七波（V62.007）— 35件（team_id 前半）
- `chart_record_templates.fk_crt_team` (chart → team)
- `match_requests.fk_mr_team` (matching → team)
- `chart_section_settings.fk_css_team` (chart → team)
- `chart_intake_form_templates.fk_cift_team` (chart → team)
- `match_reviews.fk_mrev_reviewer` (matching → team) ※idx_mrev_reviewer_team 追加
- `match_reviews.fk_mrev_reviewee` (matching → team)
- `match_proposals.fk_mp_proposing_team` (matching → team)
- `match_proposals.fk_mp_cancelled_by` (matching → team) ※idx_mp_cancelled_by_team 追加
- `equipment_items.fk_ei_team` (equipment → team)
- `service_records.fk_sr_team` (service → team)
- `service_record_settings.fk_srs_team` (service → team)
- `team_member_info_fields.fk_tmif_team` (member → team)
- `service_record_fields.fk_srf_team` (service → team)
- `chart_custom_fields.fk_ccf_team` (chart → team)
- `chart_records.fk_cr_team` (chart → team)
- `service_record_templates.fk_srt_team` (service → team)
- `performance_metrics.fk_pm_team` (performance → team)
- `tournament_promotion_records.fk_tpr_team` (tournament → team)
- `team_friend_folders.fk_tff_team` (social → team)
- `ticket_books.fk_tb_team` (ticket → team)
- `tournament_participants.fk_tourn_part_team` (tournament → team)
- `friend_content_forwards.fk_fcf_source_team` (social → team)
- `friend_content_forwards.fk_fcf_forwarding_team` (social → team)
- `team_friends.fk_tf_team_a` (social → team)
- `team_friends.fk_tf_team_b` (social → team)
- `ticket_payments.fk_tpay_team` (ticket → team)
- `ng_teams.fk_ng_blocked` (matching → team)
- `ng_teams.fk_ng_team` (matching → team)
- `payment_items.fk_pi_team` (payment → team)
- `match_request_templates.fk_mrt_team` (matching → team)
- `team_access_requirements.fk_tar_team` (payment → team)
- `ticket_products.fk_ticket_prod_team` (ticket → team)
- `match_notification_preferences.fk_mnp_team` (matching → team)
- `proxy_vote_sessions.fk_pvs_team` (proxy → team) ※idx_pvs_team_id 追加
- `daily_attendance_records.fk_dar_team` (attendance → team)

## 第八波以降（team_id 参照 残件）

主な候補：
- `reservation_lines/slots/reservations` (reservation → team)
- その他 team_id FK 残件

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
