# Phase 1-A クロスドメインFK 撤廃 — 残波 TODO

第一波（V62.001）で 9 件処理。第二波（V62.002）で 9 件処理。第三波（V62.003）で 12 件処理。第四波（V62.004）で 13 件処理。第五波（V62.005）で 11 件処理（organization_id 参照初波）。第十二波（V62.012）で 30 件処理（circulation/bulletin/mentions/corkboard/sns/tournament/line/direct_mail/ticket/committee/social 系 user_id FK 第四陣）。
残りは `users` 参照 多数 + `teams` 多数 + `organizations` 残件 + その他多数。

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

## 完了済み（user_id 参照 追加分）

### 第十二波（V62.012）— 30件（user_id 第四陣）

#### circulation ドメイン（3件）
- `circulation_recipients.fk_circulation_recipients_user` (circulation → user, ON DELETE CASCADE)
- `circulation_documents.fk_circulation_documents_created_by` (circulation → user, ON DELETE RESTRICT) ※idx追加
- `circulation_comments.fk_circulation_comments_user` (circulation → user, ON DELETE CASCADE) ※idx追加

#### bulletin ドメイン（3件）
- `bulletin_replies.fk_bulletin_replies_author` (bulletin → user, ON DELETE SET NULL) ※idx追加
- `bulletin_read_status.fk_bulletin_read_status_user` (bulletin → user, ON DELETE CASCADE)
- `bulletin_threads.fk_bulletin_threads_author` (bulletin → user, ON DELETE SET NULL) ※idx追加

#### mention ドメイン（2件）
- `mentions.fk_mention_user` (mentions → user, ON DELETE CASCADE)
- `mentions.fk_mention_by` (mentions → user, ON DELETE CASCADE) ※idx追加

#### corkboard ドメイン（2件）
- `corkboards.fk_cb_owner` (corkboard → user, ON DELETE CASCADE)
- `corkboard_cards.fk_cc_created_by` (corkboard → user) ※idx追加

#### sns ドメイン（1件）
- `sns_feed_configs.fk_sfc_configured_by` (sns → user) ※idx追加

#### tournament ドメイン（6件）
- `tournament_promotion_records.fk_tpr_executed_by` (tournament → user) ※idx追加
- `tournament_templates.fk_tt_created_by` (tournament → user) ※idx追加
- `tournaments.fk_t_created_by` (tournament → user) ※idx追加
- `tournament_individual_rankings.fk_tir_user` (tournament → user, ON DELETE CASCADE)
- `tournament_match_rosters.fk_tmr_user` (tournament → user, ON DELETE CASCADE)
- `tournament_match_player_stats.fk_tmps_user` (tournament → user, ON DELETE CASCADE)

#### line ドメイン（2件）
- `user_line_connections.fk_ulc_user_id` (line → user)
- `line_bot_configs.fk_lbc_configured_by` (line → user) ※idx追加

#### direct_mail ドメイン（4件）
- `direct_mail_templates.fk_dmt_created_by` (direct_mail → user) ※idx追加
- `direct_mail_image_uploads.fk_dmiu_uploaded_by` (direct_mail → user) ※idx追加
- `direct_mail_logs.fk_dml_sender` (direct_mail → user)
- `direct_mail_recipients.fk_dmr_user` (direct_mail → user)

#### ticket ドメイン（3件）
- `ticket_consumptions.fk_tc_voided_by` (ticket → user, NULL可) ※idx追加
- `ticket_consumptions.fk_tc_consumed_by` (ticket → user) ※idx追加
- `ticket_books.fk_tb_issued_by` (ticket → user, NULL可) ※idx追加

#### committee ドメイン（2件）
- `committee_members.fk_cm_user` (committee → user, ON DELETE CASCADE)
- `committee_members.fk_cm_invited_by` (committee → user, ON DELETE SET NULL) ※idx追加

#### social ドメイン（2件）
- `friend_content_forwards.fk_fcf_forwarded_by` (social → user, ON DELETE RESTRICT)
- `friend_content_forwards.fk_fcf_revoked_by` (social → user, ON DELETE SET NULL) ※idx追加

## 第十三波以降（user_id 参照 残件）

主な候補（V2.x, V3.x, V10.x, V11.x 系列）：
- `schedules.fk_sch_user` / `schedules.fk_sch_created_by` (schedule)
- `report_actions.fk_report_actions_user` (admin)
- `moderation_appeals.fk_ma_user` (admin)
- `user_roles.fk_user_roles_user` / `fk_user_roles_granted_by` (auth/role)
- `team_org_memberships.fk_team_org_memberships_invited_by` 等 (team)
- `presence_events.fk_pe_user` (team)
- V11.x 系列（budget, skill, kb等）多数
- V18.x 系列（attendance等）多数

## 第十三波以降（team_id 参照 残件）

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
