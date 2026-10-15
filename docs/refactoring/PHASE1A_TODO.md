# Phase 1-A クロスドメインFK 撤廃 — 残波 TODO

第一波（V62.001）で 9 件処理。第二波（V62.002）で 9 件処理。第三波（V62.003）で 12 件処理。第四波（V62.004）で 13 件処理。第五波（V62.005）で 11 件処理（organization_id 参照初波）。第六波（V62.006）で 20 件処理（organization_id 参照 残件全件 — **org_id 完全クローズ**）。第七波（V62.007）・第八波（V62.008）で team_id FK 撤廃 完全クローズ。第九波（V62.009）〜第十四波（V62.014）で user_id FK 残件 順次撤廃（各30件）。第十五波（V62.015）でさらに残件を撤廃。第十六波（V62.016）で user_id FK 全件最終撤廃 — **user_id 完全クローズ**。

## 🟢 user_id 参照 — 完全クローズ（V62.001〜V62.016 で全件撤廃済）

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

## 完了済み（user_id 参照 — wave9〜16）

### 第九波〜第十四波（V62.009〜V62.014）— 各30件（計180件）
wave9〜14 詳細は各 SQL ファイル参照。user_roles/permission_groups/team系/chat系/timeline/bulletin/seal/shift/circulation/mentions/corkboard/tournament/committee/facility/chart/service/receipt/proxy_vote/signage/incident_assignments 等を処理。

### 第十六波（V62.016）— 最終陣（**user_id 完全クローズ**）
- `yabai_unflag_requests.fk_yur_user` (moderation)
- `incident_maintenance_schedules.fk_ims_default_assignee_user`, `fk_ims_created_by` (maintenance)
- `member_skills.fk_ms_user`, `fk_ms_verified_by` (member)
- `webauthn_credentials.fk_webauthn_credentials_user` (auth)
- `platform_announcements.fk_platform_ann_created_by` (admin)
- `ad_rate_cards.fk_ad_rate_cards_created_by`, `ad_credit_limit_requests.fk_ad_credit_requests_reviewer`, `ad_conversions.fk_ad_conversions_user` (advertising)
- `kb_pages.fk_kbp_created_by`, `fk_kbp_last_edited_by`, `kb_page_pins.fk_kbpp_pinned_by`, `kb_page_revisions.fk_kbpr_editor`, `kb_templates.fk_kbt_created_by` (knowledge)
- `incident_comment_attachments.fk_ica_created_by`, `incident_categories.fk_ic_created_by`, `incident_comments.fk_ico_user`, `incident_status_histories.fk_ish_changed_by`, `incidents.fk_inc_reported_by` (incident)
- `budget_fiscal_years.fk_bfy_created_by`, `budget_transactions.fk_bt_recorded_by`, `budget_reports.fk_br_generated_by` (budget)
- `onboarding_templates.fk_ot_created_by`, `system_onboarding_presets.fk_sop_created_by` (onboarding)
- `analytics_alert_rules.fk_analytics_alert_rules_user` (analytics)
- `skill_categories.fk_skcat_created_by` (skill)
- `timetable_changes.fk_tc_created_by` (timetable)
- `class_homerooms.fk_ch_homeroom_user`, `fk_ch_created_by` (education)
- `dashboard_widget_role_visibility.fk_dwrv_updated_by` (dashboard)
- `schedule_cross_refs.fk_scr_invited_by` (schedule)
- `event_survey_responses.fk_esr_user` (event)
- `queue_counters.fk_qcnt_created_by`, `queue_tickets.fk_qtkt_user`, `fk_qtkt_cancelled_by` (queue)
- `safety_check_templates.fk_sct_created_by`, `safety_checks.fk_sc_created_by`, `fk_sc_closed_by`, `safety_response_followups.fk_srf_assigned_to` (safety)
- `user_schedule_google_events.fk_usge_user` (calendar)
- `quick_memos.fk_quick_memos_user`, `user_quick_memo_settings.fk_uqms_user`, `pending_uploads.fk_pending_uploads_user` (memo)
- `family_attendance_notices.fk_fan_student`, `fk_fan_submitter`, `fk_fan_acknowledged` (attendance)
- `period_attendance_records.fk_par_student`, `fk_par_recorded_by`, `fk_par_teacher_user` (attendance)
- `attendance_transition_alerts.fk_ata_student`, `fk_ata_resolved_by` (attendance)
- `daily_attendance_records.fk_dar_student`, `fk_dar_recorded_by` (attendance)
- `proxy_input_records.fk_pir_subject`, `fk_pir_proxy` (proxy)
- `shift_swap_requests.fk_ssw_requester`, `fk_ssw_accepter`, `fk_ssw_resolved_by` (shift)
- `shift_assignments.fk_shift_assignments_assigned_by` (shift)
- `user_voice_input_consents.fk_uvic_user` (voice)
- `recruitment_user_penalties.fk_rup_user`, `recruitment_no_show_records.fk_rns_user` (recruitment)
- `shopping_lists.fk_sl_user`, `shopping_list_items.fk_sli_created`, `fk_sli_assigned`, `fk_sli_checked` (shopping)
- `checkin_locations.fk_cl_created_by`, `member_card_checkins.fk_mcc_checked_in_by` (checkin)
- `team_care_notification_overrides.fk_tcno_created_by` (care)
- `users.fk_users_watcher` (care / 自己参照)
- `announcement_range_templates.fk_art_created_by` (announcement)

## 次フェーズ（team_id 参照 残件 — Phase 1-B 候補）

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
