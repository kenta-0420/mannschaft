# Phase 1-A クロスドメインFK 撤廃 — 残波 TODO

第一波（V62.001）で 9 件処理。第二波（V62.002）で 9 件処理。第三波（V62.003）で 12 件処理。第四波（V62.004）で 13 件処理。第五波（V62.005）で 11 件処理（organization_id 参照初波）。第六波（V62.006）で 20 件処理（organization_id 残件全件クローズ）。第七波（V62.007）で 35 件処理（team_id 前半）。第八波（V62.008）で 33 件処理（team_id 後半）。第九波（V62.009）で 30 件処理（role/team/social/moderation/shift 系 user_id）。第十波（V62.010）で 30 件処理（workflow/activity/blog/form/property 系 user_id）。
残りは `users` 参照 多数（schedule系・V18.x attendance系 等）。

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

### 第十波（V62.010）— 30件（user_id 第九波）
#### activity ドメイン（3件）
- `activity_templates.fk_at_created_by` (activity)
- `activity_participants.fk_ap_user` (activity)
- `activity_comments.fk_ac_user` (activity)

#### workflow ドメイン（5件）
- `workflow_request_comments.fk_wf_request_comments_user` (workflow)
- `workflow_request_attachments.fk_wf_request_attachments_uploaded_by` (workflow)
- `workflow_requests.fk_workflow_requests_requested_by` (workflow)
- `workflow_request_approvers.fk_wf_request_approvers_user` (workflow)
- `workflow_templates.fk_workflow_templates_created_by` (workflow)

#### blog ドメイン（4件）
- `blog_post_revisions.fk_bpr_editor` (blog)
- `blog_post_shares.fk_bps_shared_by` (blog)
- `user_blog_settings.fk_ubs_user` (blog)
- `blog_image_uploads.fk_biu_uploader` (blog)

#### form ドメイン（4件）
- `survey_targets.fk_survey_targets_user` (form)
- `form_submissions.fk_form_submissions_submitted_by` (form)
- `form_templates.fk_form_templates_created_by` (form)
- `system_form_presets.fk_system_form_presets_created_by` (form)

#### storage ドメイン（4件）
- `shared_file_stars.fk_file_stars_user` (storage)
- `shared_file_links.fk_file_links_created` (storage)
- `shared_file_comments.fk_file_comments_user` (storage)
- `shared_file_tags.fk_file_tags_user` (storage)

#### membership ドメイン（1件）
- `member_positions.fk_member_positions_assigned_by` (membership)

#### property / disclosure ドメイン（9件）
- `property_work_history_views.fk_pwhv_user` (property)
- `disclosure_form_drafts.fk_dfd_created_by` (property)
- `disclosure_form_drafts.fk_dfd_updated_by` (property)
- `photo_albums.fk_pa_created_by` (photo)
- `disclosure_exports.fk_de_requester` (property)
- `disclosure_form_templates.fk_dft_created_by` (property)
- `property_work_packages.fk_pwp_created_by` (property)
- `vendors.fk_vendors_created_by` (property)
- `property_work_documents.fk_pwd_created_by` (property)

## 第十一波以降（user_id 参照 残件）

主な候補（V2.x, V3.x, V10.x, V11.x 系列）：
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
