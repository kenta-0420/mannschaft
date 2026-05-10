-- Phase 1-A wave16: user_id クロスドメインFK撤廃 最終陣（user_id 完全クローズ）
--
-- 1000万ユーザー耐久DB再構築 Phase 1-A 第十六波（最終波）。
-- wave1〜15 で処理できなかった残件をすべて撤廃し、
-- user_id → users のクロスドメインFK を完全クローズする。
--
-- CLAUDE.md §1「クロスドメインFKは作らない」原則に従い撤廃。
-- users は論理削除（deleted_at）+ 退会時匿名化（UserEntity.anonymize()）で管理されており、
-- 物理削除は発生しない。CASCADE 撤廃による孤児行の発生は理論上ない。
--
-- ━━━ 対象一覧（72件） ━━━
--
--   moderation ドメイン:
--     yabai_unflag_requests.fk_yur_user
--
--   maintenance ドメイン:
--     incident_maintenance_schedules.fk_ims_default_assignee_user
--     incident_maintenance_schedules.fk_ims_created_by
--
--   member ドメイン:
--     member_skills.fk_ms_user
--     member_skills.fk_ms_verified_by
--
--   auth ドメイン:
--     webauthn_credentials.fk_webauthn_credentials_user
--
--   admin ドメイン:
--     platform_announcements.fk_platform_ann_created_by
--
--   advertising ドメイン:
--     ad_rate_cards.fk_ad_rate_cards_created_by
--     ad_credit_limit_requests.fk_ad_credit_requests_reviewer
--     ad_conversions.fk_ad_conversions_user
--
--   knowledge ドメイン:
--     kb_pages.fk_kbp_created_by
--     kb_pages.fk_kbp_last_edited_by
--     kb_page_pins.fk_kbpp_pinned_by
--     incident_comment_attachments.fk_ica_created_by
--     incident_categories.fk_ic_created_by
--     incident_comments.fk_ico_user
--     kb_page_revisions.fk_kbpr_editor
--
--   budget ドメイン:
--     budget_fiscal_years.fk_bfy_created_by
--     budget_reports.fk_br_generated_by (idx 既存: idx_br_generated_by)
--     budget_transactions.fk_bt_recorded_by (idx 既存: idx_bt_recorded_by)
--
--   onboarding ドメイン:
--     onboarding_templates.fk_ot_created_by (idx 既存: idx_ot_created_by)
--     system_onboarding_presets.fk_sop_created_by
--
--   kb ドメイン:
--     kb_templates.fk_kbt_created_by
--
--   incident ドメイン:
--     incident_status_histories.fk_ish_changed_by
--
--   analytics ドメイン:
--     analytics_alert_rules.fk_analytics_alert_rules_user
--
--   skill ドメイン:
--     skill_categories.fk_skcat_created_by (idx 既存: idx_skcat_created_by)
--
--   timetable ドメイン:
--     timetable_changes.fk_tc_created_by
--
--   incident ドメイン:
--     incidents.fk_inc_reported_by (idx 既存: idx_inc_reported_by)
--
--   education ドメイン:
--     class_homerooms.fk_ch_homeroom_user (idx 既存: idx_ch_homeroom_teacher)
--     class_homerooms.fk_ch_created_by
--
--   dashboard ドメイン:
--     dashboard_widget_role_visibility.fk_dwrv_updated_by
--
--   schedule ドメイン:
--     schedule_cross_refs.fk_scr_invited_by
--
--   event ドメイン:
--     event_survey_responses.fk_esr_user (idx 既存: idx_esr_user_id)
--
--   queue ドメイン:
--     queue_counters.fk_qcnt_created_by
--     queue_tickets.fk_qtkt_user (idx 既存: idx_qtkt_user_date)
--     queue_tickets.fk_qtkt_cancelled_by
--
--   safety ドメイン:
--     safety_check_templates.fk_sct_created_by
--     safety_checks.fk_sc_created_by
--     safety_checks.fk_sc_closed_by
--     safety_response_followups.fk_srf_assigned_to
--
--   calendar ドメイン:
--     user_schedule_google_events.fk_usge_user (idx 既存: uq_usge_user_schedule 先頭カラム)
--
--   memo ドメイン:
--     quick_memos.fk_quick_memos_user (idx 既存: idx_quick_memos_user_status)
--     user_quick_memo_settings.fk_uqms_user (PK = user_id)
--     pending_uploads.fk_pending_uploads_user (idx 既存: idx_pending_uploads_user_created)
--
--   attendance ドメイン:
--     family_attendance_notices.fk_fan_acknowledged
--     family_attendance_notices.fk_fan_student (idx 既存: idx_fan_student)
--     family_attendance_notices.fk_fan_submitter
--     period_attendance_records.fk_par_student (idx 既存: idx_par_student_date)
--     period_attendance_records.fk_par_recorded_by
--     period_attendance_records.fk_par_teacher_user (idx 既存: idx_par_teacher)
--     attendance_transition_alerts.fk_ata_resolved_by
--     attendance_transition_alerts.fk_ata_student (idx 既存: idx_ata_student_date)
--     daily_attendance_records.fk_dar_student (idx 既存: idx_dar_student)
--     daily_attendance_records.fk_dar_recorded_by
--
--   proxy ドメイン:
--     proxy_input_records.fk_pir_subject (idx 既存: idx_pir_subject_feature)
--     proxy_input_records.fk_pir_proxy (idx 既存: idx_pir_proxy_created)
--
--   shift ドメイン:
--     shift_swap_requests.fk_ssw_resolved_by
--     shift_swap_requests.fk_ssw_requester (idx 既存: idx_ssw_requester_status)
--     shift_swap_requests.fk_ssw_accepter
--     shift_assignments.fk_shift_assignments_assigned_by
--
--   voice ドメイン:
--     user_voice_input_consents.fk_uvic_user (idx 既存: idx_uvic_user_version)
--
--   recruitment ドメイン:
--     recruitment_user_penalties.fk_rup_user (idx 既存: idx_rup_user_scope)
--     recruitment_no_show_records.fk_rns_user (idx 既存: idx_rns_user_id)
--
--   shopping ドメイン:
--     shopping_lists.fk_sl_user
--     shopping_list_items.fk_sli_created
--     shopping_list_items.fk_sli_assigned (idx 既存: idx_sli_assigned)
--     shopping_list_items.fk_sli_checked
--
--   checkin ドメイン:
--     checkin_locations.fk_cl_created_by
--     member_card_checkins.fk_mcc_checked_in_by (idx 既存: idx_mcc_checked_in_by)
--
--   care ドメイン:
--     team_care_notification_overrides.fk_tcno_created_by
--     users.fk_users_watcher （users 自己参照だが「user → user」はクロスドメインと同等の扱い）
--
--   announcement ドメイン:
--     announcement_range_templates.fk_art_created_by (idx 既存: idx_art_created_by)
--
-- ━━━ index 追加が必要なもの ━━━
--   incident_maintenance_schedules : default_assignee_user_id / created_by → 追加
--   member_skills                  : verified_by → 追加
--   webauthn_credentials           : user_id → 追加
--   platform_announcements         : created_by → 追加
--   ad_rate_cards                  : created_by → 追加
--   ad_credit_limit_requests       : reviewed_by → 追加
--   ad_conversions                 : converted_user_id → 追加
--   kb_pages                       : created_by / last_edited_by → 追加
--   kb_page_pins                   : pinned_by → 追加
--   incident_comment_attachments   : created_by → 追加
--   incident_categories            : created_by → 追加
--   incident_comments              : user_id → 追加
--   kb_page_revisions              : editor_id → 追加
--   budget_fiscal_years            : created_by → 追加
--   kb_templates                   : created_by → 追加
--   incident_status_histories      : changed_by → 追加
--   analytics_alert_rules          : created_by → 追加
--   system_onboarding_presets      : created_by → 追加
--   timetable_changes              : created_by → 追加
--   class_homerooms                : created_by → 追加
--   dashboard_widget_role_visibility: updated_by → 追加
--   schedule_cross_refs            : invited_by → 追加
--   queue_counters                 : created_by → 追加
--   queue_tickets                  : cancelled_by → 追加
--   safety_check_templates         : created_by → 追加
--   safety_checks                  : created_by / closed_by → 追加
--   safety_response_followups      : assigned_to → 追加
--   family_attendance_notices      : submitter_user_id / acknowledged_by → 追加
--   period_attendance_records      : recorded_by → 追加
--   attendance_transition_alerts   : resolved_by → 追加
--   daily_attendance_records       : recorded_by → 追加
--   shift_swap_requests            : accepter_id / resolved_by → 追加
--   shift_assignments              : assigned_by → 追加
--   shopping_lists                 : created_by → 追加
--   shopping_list_items            : created_by / checked_by → 追加
--   checkin_locations              : created_by → 追加
--   team_care_notification_overrides: created_by → 追加
--   users(fk_users_watcher)        : account_created_by_watcher_user_id → 追加

-- =============================================================================
-- moderation ドメイン
-- =============================================================================
-- yabai_unflag_requests.fk_yur_user
-- user_id は INDEX idx_yur_user(user_id, created_at DESC) でカバー済 → 追加不要
ALTER TABLE yabai_unflag_requests DROP FOREIGN KEY fk_yur_user;

-- =============================================================================
-- maintenance ドメイン
-- =============================================================================
-- incident_maintenance_schedules.fk_ims_default_assignee_user
-- default_assignee_user_id に index なし → 追加
ALTER TABLE incident_maintenance_schedules DROP FOREIGN KEY fk_ims_default_assignee_user;
CREATE INDEX idx_ims_default_assignee ON incident_maintenance_schedules (default_assignee_user_id);

-- incident_maintenance_schedules.fk_ims_created_by
-- created_by に index なし → 追加
ALTER TABLE incident_maintenance_schedules DROP FOREIGN KEY fk_ims_created_by;
CREATE INDEX idx_ims_created_by ON incident_maintenance_schedules (created_by);

-- =============================================================================
-- member ドメイン
-- =============================================================================
-- member_skills.fk_ms_user
-- user_id は INDEX idx_ms_user_scope(user_id, scope_type, scope_id) でカバー済 → 追加不要
ALTER TABLE member_skills DROP FOREIGN KEY fk_ms_user;

-- member_skills.fk_ms_verified_by
-- verified_by に index なし → 追加
ALTER TABLE member_skills DROP FOREIGN KEY fk_ms_verified_by;
CREATE INDEX idx_ms_verified_by ON member_skills (verified_by);

-- =============================================================================
-- auth ドメイン
-- =============================================================================
-- webauthn_credentials.fk_webauthn_credentials_user
-- user_id に index なし（UNIQUE uq_webauthn_credentials_credential_id のみ）→ 追加
ALTER TABLE webauthn_credentials DROP FOREIGN KEY fk_webauthn_credentials_user;
CREATE INDEX idx_webauthn_credentials_user_id ON webauthn_credentials (user_id);

-- =============================================================================
-- admin ドメイン
-- =============================================================================
-- platform_announcements.fk_platform_ann_created_by
-- created_by に index なし → 追加
ALTER TABLE platform_announcements DROP FOREIGN KEY fk_platform_ann_created_by;
CREATE INDEX idx_platform_ann_created_by ON platform_announcements (created_by);

-- =============================================================================
-- advertising ドメイン
-- =============================================================================
-- ad_rate_cards.fk_ad_rate_cards_created_by
-- created_by に index なし → 追加
ALTER TABLE ad_rate_cards DROP FOREIGN KEY fk_ad_rate_cards_created_by;
CREATE INDEX idx_ad_rate_cards_created_by ON ad_rate_cards (created_by);

-- ad_credit_limit_requests.fk_ad_credit_requests_reviewer
-- reviewed_by に index なし → 追加
ALTER TABLE ad_credit_limit_requests DROP FOREIGN KEY fk_ad_credit_requests_reviewer;
CREATE INDEX idx_ad_credit_limit_reviewed_by ON ad_credit_limit_requests (reviewed_by);

-- ad_conversions.fk_ad_conversions_user
-- converted_user_id に index なし（既存は campaign_id / click_id / ad_id 軸）→ 追加
ALTER TABLE ad_conversions DROP FOREIGN KEY fk_ad_conversions_user;
CREATE INDEX idx_ad_conversions_user_id ON ad_conversions (converted_user_id);

-- =============================================================================
-- knowledge ドメイン（kb_pages, kb_page_pins, kb_page_revisions, kb_templates）
-- =============================================================================
-- kb_pages.fk_kbp_created_by
-- created_by に index なし → 追加
ALTER TABLE kb_pages DROP FOREIGN KEY fk_kbp_created_by;
CREATE INDEX idx_kbp_created_by ON kb_pages (created_by);

-- kb_pages.fk_kbp_last_edited_by
-- last_edited_by に index なし → 追加
ALTER TABLE kb_pages DROP FOREIGN KEY fk_kbp_last_edited_by;
CREATE INDEX idx_kbp_last_edited_by ON kb_pages (last_edited_by);

-- kb_page_pins.fk_kbpp_pinned_by
-- pinned_by に index なし → 追加
ALTER TABLE kb_page_pins DROP FOREIGN KEY fk_kbpp_pinned_by;
CREATE INDEX idx_kbpp_pinned_by ON kb_page_pins (pinned_by);

-- kb_page_revisions.fk_kbpr_editor
-- editor_id に index なし → 追加
ALTER TABLE kb_page_revisions DROP FOREIGN KEY fk_kbpr_editor;
CREATE INDEX idx_kbpr_editor_id ON kb_page_revisions (editor_id);

-- kb_templates.fk_kbt_created_by
-- created_by に index なし → 追加
ALTER TABLE kb_templates DROP FOREIGN KEY fk_kbt_created_by;
CREATE INDEX idx_kbt_created_by ON kb_templates (created_by);

-- =============================================================================
-- incident ドメイン（incident_comment_attachments, incident_categories,
--                    incident_comments, incident_status_histories, incidents）
-- =============================================================================
-- incident_comment_attachments.fk_ica_created_by
-- created_by に index なし → 追加
ALTER TABLE incident_comment_attachments DROP FOREIGN KEY fk_ica_created_by;
CREATE INDEX idx_ica_created_by ON incident_comment_attachments (created_by);

-- incident_categories.fk_ic_created_by
-- created_by に index なし → 追加
ALTER TABLE incident_categories DROP FOREIGN KEY fk_ic_created_by;
CREATE INDEX idx_ic_created_by ON incident_categories (created_by);

-- incident_comments.fk_ico_user
-- user_id に index なし → 追加
ALTER TABLE incident_comments DROP FOREIGN KEY fk_ico_user;
CREATE INDEX idx_ico_user_id ON incident_comments (user_id);

-- incident_status_histories.fk_ish_changed_by
-- changed_by に index なし → 追加
ALTER TABLE incident_status_histories DROP FOREIGN KEY fk_ish_changed_by;
CREATE INDEX idx_ish_changed_by ON incident_status_histories (changed_by);

-- incidents.fk_inc_reported_by
-- reported_by は INDEX idx_inc_reported_by(reported_by) でカバー済 → 追加不要
ALTER TABLE incidents DROP FOREIGN KEY fk_inc_reported_by;

-- =============================================================================
-- budget ドメイン
-- =============================================================================
-- budget_fiscal_years.fk_bfy_created_by
-- created_by に index なし → 追加
ALTER TABLE budget_fiscal_years DROP FOREIGN KEY fk_bfy_created_by;
CREATE INDEX idx_bfy_created_by ON budget_fiscal_years (created_by);

-- budget_transactions.fk_bt_recorded_by
-- recorded_by は INDEX idx_bt_recorded_by(recorded_by) でカバー済 → 追加不要
ALTER TABLE budget_transactions DROP FOREIGN KEY fk_bt_recorded_by;

-- budget_reports.fk_br_generated_by
-- generated_by は INDEX idx_br_generated_by(generated_by) でカバー済 → 追加不要
ALTER TABLE budget_reports DROP FOREIGN KEY fk_br_generated_by;

-- =============================================================================
-- onboarding ドメイン
-- =============================================================================
-- onboarding_templates.fk_ot_created_by
-- created_by は INDEX idx_ot_created_by(created_by) でカバー済 → 追加不要
ALTER TABLE onboarding_templates DROP FOREIGN KEY fk_ot_created_by;

-- system_onboarding_presets.fk_sop_created_by
-- created_by に index なし → 追加
ALTER TABLE system_onboarding_presets DROP FOREIGN KEY fk_sop_created_by;
CREATE INDEX idx_sop_created_by ON system_onboarding_presets (created_by);

-- =============================================================================
-- analytics ドメイン
-- =============================================================================
-- analytics_alert_rules.fk_analytics_alert_rules_user
-- created_by に index なし → 追加
ALTER TABLE analytics_alert_rules DROP FOREIGN KEY fk_analytics_alert_rules_user;
CREATE INDEX idx_analytics_alert_rules_created_by ON analytics_alert_rules (created_by);

-- =============================================================================
-- skill ドメイン
-- =============================================================================
-- skill_categories.fk_skcat_created_by
-- created_by は INDEX idx_skcat_created_by(created_by) でカバー済 → 追加不要
ALTER TABLE skill_categories DROP FOREIGN KEY fk_skcat_created_by;

-- =============================================================================
-- timetable ドメイン
-- =============================================================================
-- timetable_changes.fk_tc_created_by
-- created_by に index なし → 追加
ALTER TABLE timetable_changes DROP FOREIGN KEY fk_tc_created_by;
CREATE INDEX idx_tc_created_by ON timetable_changes (created_by);

-- =============================================================================
-- education ドメイン（class_homerooms）
-- =============================================================================
-- class_homerooms.fk_ch_homeroom_user
-- homeroom_teacher_user_id は INDEX idx_ch_homeroom_teacher(homeroom_teacher_user_id, effective_until) でカバー済 → 追加不要
ALTER TABLE class_homerooms DROP FOREIGN KEY fk_ch_homeroom_user;

-- class_homerooms.fk_ch_created_by
-- created_by に index なし → 追加
ALTER TABLE class_homerooms DROP FOREIGN KEY fk_ch_created_by;
CREATE INDEX idx_ch_created_by ON class_homerooms (created_by);

-- =============================================================================
-- dashboard ドメイン
-- =============================================================================
-- dashboard_widget_role_visibility.fk_dwrv_updated_by
-- updated_by に index なし → 追加
ALTER TABLE dashboard_widget_role_visibility DROP FOREIGN KEY fk_dwrv_updated_by;
CREATE INDEX idx_dwrv_updated_by ON dashboard_widget_role_visibility (updated_by);

-- =============================================================================
-- schedule ドメイン
-- =============================================================================
-- schedule_cross_refs.fk_scr_invited_by
-- invited_by に index なし → 追加
ALTER TABLE schedule_cross_refs DROP FOREIGN KEY fk_scr_invited_by;
CREATE INDEX idx_scr_invited_by ON schedule_cross_refs (invited_by);

-- =============================================================================
-- event ドメイン
-- =============================================================================
-- event_survey_responses.fk_esr_user
-- user_id は INDEX idx_esr_user_id(user_id) でカバー済 → 追加不要
ALTER TABLE event_survey_responses DROP FOREIGN KEY fk_esr_user;

-- =============================================================================
-- queue ドメイン
-- =============================================================================
-- queue_counters.fk_qcnt_created_by
-- created_by に index なし → 追加
ALTER TABLE queue_counters DROP FOREIGN KEY fk_qcnt_created_by;
CREATE INDEX idx_qcnt_created_by ON queue_counters (created_by);

-- queue_tickets.fk_qtkt_user
-- user_id は INDEX idx_qtkt_user_date(user_id, issued_date DESC) でカバー済 → 追加不要
ALTER TABLE queue_tickets DROP FOREIGN KEY fk_qtkt_user;

-- queue_tickets.fk_qtkt_cancelled_by
-- cancelled_by に index なし → 追加
ALTER TABLE queue_tickets DROP FOREIGN KEY fk_qtkt_cancelled_by;
CREATE INDEX idx_qtkt_cancelled_by ON queue_tickets (cancelled_by);

-- =============================================================================
-- safety ドメイン
-- =============================================================================
-- safety_check_templates.fk_sct_created_by
-- created_by に index なし → 追加
ALTER TABLE safety_check_templates DROP FOREIGN KEY fk_sct_created_by;
CREATE INDEX idx_sct_created_by ON safety_check_templates (created_by);

-- safety_checks.fk_sc_created_by
-- created_by に index なし → 追加
ALTER TABLE safety_checks DROP FOREIGN KEY fk_sc_created_by;
CREATE INDEX idx_sc_created_by ON safety_checks (created_by);

-- safety_checks.fk_sc_closed_by
-- closed_by に index なし → 追加
ALTER TABLE safety_checks DROP FOREIGN KEY fk_sc_closed_by;
CREATE INDEX idx_sc_closed_by ON safety_checks (closed_by);

-- safety_response_followups.fk_srf_assigned_to
-- assigned_to に index なし → 追加
ALTER TABLE safety_response_followups DROP FOREIGN KEY fk_srf_assigned_to;
CREATE INDEX idx_srf_assigned_to ON safety_response_followups (assigned_to);

-- =============================================================================
-- calendar ドメイン
-- =============================================================================
-- user_schedule_google_events.fk_usge_user
-- user_id は UNIQUE KEY uq_usge_user_schedule(user_id, schedule_id) の先頭カラムでカバー済 → 追加不要
ALTER TABLE user_schedule_google_events DROP FOREIGN KEY fk_usge_user;

-- =============================================================================
-- memo ドメイン
-- =============================================================================
-- quick_memos.fk_quick_memos_user
-- user_id は INDEX idx_quick_memos_user_status(user_id, status, deleted_at) でカバー済 → 追加不要
ALTER TABLE quick_memos DROP FOREIGN KEY fk_quick_memos_user;

-- user_quick_memo_settings.fk_uqms_user
-- user_id は PRIMARY KEY でカバー済 → 追加不要
ALTER TABLE user_quick_memo_settings DROP FOREIGN KEY fk_uqms_user;

-- pending_uploads.fk_pending_uploads_user
-- user_id は INDEX idx_pending_uploads_user_created(user_id, created_at) でカバー済 → 追加不要
ALTER TABLE pending_uploads DROP FOREIGN KEY fk_pending_uploads_user;

-- =============================================================================
-- attendance ドメイン（family_attendance_notices, period_attendance_records,
--                      attendance_transition_alerts, daily_attendance_records）
-- =============================================================================
-- family_attendance_notices.fk_fan_student
-- student_user_id は INDEX idx_fan_student(student_user_id, attendance_date) でカバー済 → 追加不要
ALTER TABLE family_attendance_notices DROP FOREIGN KEY fk_fan_student;

-- family_attendance_notices.fk_fan_submitter
-- submitter_user_id に index なし → 追加
ALTER TABLE family_attendance_notices DROP FOREIGN KEY fk_fan_submitter;
CREATE INDEX idx_fan_submitter ON family_attendance_notices (submitter_user_id);

-- family_attendance_notices.fk_fan_acknowledged
-- acknowledged_by に index なし → 追加
ALTER TABLE family_attendance_notices DROP FOREIGN KEY fk_fan_acknowledged;
CREATE INDEX idx_fan_acknowledged_by ON family_attendance_notices (acknowledged_by);

-- period_attendance_records.fk_par_student
-- student_user_id は INDEX idx_par_student_date(student_user_id, attendance_date) でカバー済 → 追加不要
ALTER TABLE period_attendance_records DROP FOREIGN KEY fk_par_student;

-- period_attendance_records.fk_par_recorded_by
-- recorded_by に index なし → 追加
ALTER TABLE period_attendance_records DROP FOREIGN KEY fk_par_recorded_by;
CREATE INDEX idx_par_recorded_by ON period_attendance_records (recorded_by);

-- period_attendance_records.fk_par_teacher_user
-- teacher_user_id は INDEX idx_par_teacher(teacher_user_id, attendance_date) でカバー済 → 追加不要
ALTER TABLE period_attendance_records DROP FOREIGN KEY fk_par_teacher_user;

-- attendance_transition_alerts.fk_ata_student
-- student_user_id は INDEX idx_ata_student_date(student_user_id, attendance_date) でカバー済 → 追加不要
ALTER TABLE attendance_transition_alerts DROP FOREIGN KEY fk_ata_student;

-- attendance_transition_alerts.fk_ata_resolved_by
-- resolved_by に index なし → 追加
ALTER TABLE attendance_transition_alerts DROP FOREIGN KEY fk_ata_resolved_by;
CREATE INDEX idx_ata_resolved_by ON attendance_transition_alerts (resolved_by);

-- daily_attendance_records.fk_dar_student
-- student_user_id は INDEX idx_dar_student(student_user_id, attendance_date) でカバー済 → 追加不要
ALTER TABLE daily_attendance_records DROP FOREIGN KEY fk_dar_student;

-- daily_attendance_records.fk_dar_recorded_by
-- recorded_by に index なし → 追加
ALTER TABLE daily_attendance_records DROP FOREIGN KEY fk_dar_recorded_by;
CREATE INDEX idx_dar_recorded_by ON daily_attendance_records (recorded_by);

-- =============================================================================
-- proxy ドメイン
-- =============================================================================
-- proxy_input_records.fk_pir_subject
-- subject_user_id は INDEX idx_pir_subject_feature(subject_user_id, feature_scope, created_at) でカバー済 → 追加不要
ALTER TABLE proxy_input_records DROP FOREIGN KEY fk_pir_subject;

-- proxy_input_records.fk_pir_proxy
-- proxy_user_id は INDEX idx_pir_proxy_created(proxy_user_id, created_at) でカバー済 → 追加不要
ALTER TABLE proxy_input_records DROP FOREIGN KEY fk_pir_proxy;

-- =============================================================================
-- shift ドメイン
-- =============================================================================
-- shift_swap_requests.fk_ssw_requester
-- requester_id は INDEX idx_ssw_requester_status(requester_id, status) でカバー済 → 追加不要
ALTER TABLE shift_swap_requests DROP FOREIGN KEY fk_ssw_requester;

-- shift_swap_requests.fk_ssw_accepter
-- accepter_id に index なし → 追加
ALTER TABLE shift_swap_requests DROP FOREIGN KEY fk_ssw_accepter;
CREATE INDEX idx_ssw_accepter_id ON shift_swap_requests (accepter_id);

-- shift_swap_requests.fk_ssw_resolved_by
-- resolved_by に index なし → 追加
ALTER TABLE shift_swap_requests DROP FOREIGN KEY fk_ssw_resolved_by;
CREATE INDEX idx_ssw_resolved_by ON shift_swap_requests (resolved_by);

-- shift_assignments.fk_shift_assignments_assigned_by
-- assigned_by に index なし（idx_shift_assignments_user_id は user_id 用）→ 追加
ALTER TABLE shift_assignments DROP FOREIGN KEY fk_shift_assignments_assigned_by;
CREATE INDEX idx_shift_assignments_assigned_by ON shift_assignments (assigned_by);

-- =============================================================================
-- voice ドメイン
-- =============================================================================
-- user_voice_input_consents.fk_uvic_user
-- user_id は INDEX idx_uvic_user_version(user_id, version, revoked_at) でカバー済 → 追加不要
ALTER TABLE user_voice_input_consents DROP FOREIGN KEY fk_uvic_user;

-- =============================================================================
-- recruitment ドメイン
-- =============================================================================
-- recruitment_user_penalties.fk_rup_user
-- user_id は INDEX idx_rup_user_scope(user_id, scope_type, scope_id) でカバー済 → 追加不要
ALTER TABLE recruitment_user_penalties DROP FOREIGN KEY fk_rup_user;

-- recruitment_no_show_records.fk_rns_user
-- user_id は INDEX idx_rns_user_id(user_id) でカバー済 → 追加不要
ALTER TABLE recruitment_no_show_records DROP FOREIGN KEY fk_rns_user;

-- =============================================================================
-- shopping ドメイン
-- =============================================================================
-- shopping_lists.fk_sl_user
-- created_by に index なし → 追加
ALTER TABLE shopping_lists DROP FOREIGN KEY fk_sl_user;
CREATE INDEX idx_sl_created_by ON shopping_lists (created_by);

-- shopping_list_items.fk_sli_assigned
-- assigned_to は INDEX idx_sli_assigned(assigned_to, is_checked) でカバー済 → 追加不要
ALTER TABLE shopping_list_items DROP FOREIGN KEY fk_sli_assigned;

-- shopping_list_items.fk_sli_checked
-- checked_by に index なし → 追加
ALTER TABLE shopping_list_items DROP FOREIGN KEY fk_sli_checked;
CREATE INDEX idx_sli_checked_by ON shopping_list_items (checked_by);

-- shopping_list_items.fk_sli_created
-- created_by に index なし → 追加
ALTER TABLE shopping_list_items DROP FOREIGN KEY fk_sli_created;
CREATE INDEX idx_sli_created_by ON shopping_list_items (created_by);

-- =============================================================================
-- checkin ドメイン
-- =============================================================================
-- checkin_locations.fk_cl_created_by
-- created_by に index なし → 追加
ALTER TABLE checkin_locations DROP FOREIGN KEY fk_cl_created_by;
CREATE INDEX idx_cl_created_by ON checkin_locations (created_by);

-- member_card_checkins.fk_mcc_checked_in_by
-- checked_in_by は INDEX idx_mcc_checked_in_by(checked_in_by, checked_in_at DESC) でカバー済 → 追加不要
ALTER TABLE member_card_checkins DROP FOREIGN KEY fk_mcc_checked_in_by;

-- =============================================================================
-- care ドメイン
-- =============================================================================
-- team_care_notification_overrides.fk_tcno_created_by
-- created_by に index なし → 追加
ALTER TABLE team_care_notification_overrides DROP FOREIGN KEY fk_tcno_created_by;
CREATE INDEX idx_tcno_created_by ON team_care_notification_overrides (created_by);

-- users.fk_users_watcher（users 内の account_created_by_watcher_user_id → users.id）
-- account_created_by_watcher_user_id に index なし → 追加
ALTER TABLE users DROP FOREIGN KEY fk_users_watcher;
CREATE INDEX idx_users_watcher ON users (account_created_by_watcher_user_id);

-- =============================================================================
-- announcement ドメイン
-- =============================================================================
-- announcement_range_templates.fk_art_created_by
-- created_by は INDEX idx_art_created_by(created_by) でカバー済 → 追加不要
ALTER TABLE announcement_range_templates DROP FOREIGN KEY fk_art_created_by;
