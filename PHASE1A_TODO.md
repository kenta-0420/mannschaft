# Phase 1-A クロスドメインFK 撤廃 — 残波 TODO

第一波（V62.001）で 9 件処理。第二波（V62.002）で 9 件処理。第三波（V62.003）で 12 件処理。第四波（V62.004）で 13 件処理。第五波（V62.005）で 11 件処理（organization_id 参照初波）。第六波（V62.006）で 20 件処理（organization_id 参照 残件全件 — **org_id 完全クローズ**）。第十五波（V62.015）で 30 件処理（job/timetable/incident/ad 系）。
残りは `users` 参照 多数 + `teams` 多数 + その他多数。

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

### 第十五波（V62.015）— 30件（user_id 参照 第七陣）

#### translation ドメイン
- `content_translations.fk_ct_translator` (translator_id) ※ index 既存
- `content_translations.fk_ct_reviewer` (reviewer_id) ※ idx_ct_reviewer 追加
- `translation_assignments.fk_transassign_user` (user_id) ※ index 既存

#### incident ドメイン
- `incident_attachments.fk_ia_created_by` (created_by) ※ idx_ia_created_by 追加

#### knowledge ドメイン
- `kb_image_uploads.fk_kbiu_uploader` (uploader_id) ※ index 既存

#### equipment ドメイン
- `equipment_ranking_exclusions.fk_ere_user` (excluded_by_user_id) ※ idx_ere_excluded_by_user_id 追加

#### auth ドメイン
- `otp_challenges.fk_otp_challenges_user` (user_id) ※ index 既存

#### api ドメイン
- `api_keys.fk_ak_created_by` (created_by) ※ idx_ak_created_by 追加

#### signage ドメイン
- `signage_access_tokens.fk_sat_created_by` (created_by) ※ idx_sat_created_by 追加

#### jobmatching ドメイン
- `job_contracts.fk_jc_worker` (worker_user_id) ※ index 既存
- `job_contracts.fk_jc_requester` (requester_user_id) ※ index 既存
- `job_applications.fk_ja_applicant` (applicant_user_id) ※ index 既存
- `job_applications.fk_ja_decider` (decided_by_user_id) ※ idx_ja_decided_by_user_id 追加
- `job_qr_tokens.fk_jqt_issuer` (issued_by_user_id) ※ idx_jqt_issued_by_user_id 追加
- `job_check_ins.fk_jci_worker` (worker_user_id) ※ index 既存

#### timetable ドメイン
- `timetable_slot_user_notes.fk_tsun_user` (user_id) ※ index 既存
- `timetable_slot_user_note_fields.fk_tsunf_user` (user_id) ※ index 既存
- `timetable_slot_user_note_attachments.fk_tsuna_user` (user_id) ※ index 既存

#### team ドメイン
- `team_enabled_modules.fk_team_enabled_modules_user` (enabled_by) ※ idx_team_enabled_modules_enabled_by 追加

#### attendance ドメイン
- `attendance_requirement_evaluations.fk_are_student` (student_user_id) ※ index 既存

#### committee ドメイン
- `committee_distribution_logs.fk_cdl_created_by` (created_by) ※ idx_cdl_created_by 追加

#### error_report ドメイン
- `error_report_occurrences.fk_ero_user_id` (user_id) ※ idx_ero_user_id 追加

#### notification ドメイン
- `confirmable_notifications.fk_cn_cancelled_by` (cancelled_by) ※ idx_cn_cancelled_by 追加
- `confirmable_notifications.fk_cn_created_by` (created_by) ※ index 既存

#### webhook ドメイン
- `webhook_endpoints.fk_we_created_by` (created_by) ※ idx_we_created_by 追加

#### announcement ドメイン
- `announcement_feeds.fk_af_author` (author_id) ※ index 既存

#### feedback ドメイン
- `feedback_submissions.fk_fs_submitted_by` (submitted_by) ※ index 既存

#### schedule ドメイン
- `schedule_annual_copy_logs.fk_sacl_executed_by` (executed_by) ※ idx_sacl_executed_by 追加

#### advertising ドメイン
- `ad_report_schedules.fk_ad_report_schedules_user` (created_by) ※ idx_ad_report_schedules_created_by 追加

#### admin ドメイン
- `warning_re_reviews.fk_wrr_user` (user_id) ※ UNIQUE KEY uq_wrr_user_action でカバー済み

**残件**: `attendance_requirement_evaluations.fk_are_resolver` は次波で処理

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
