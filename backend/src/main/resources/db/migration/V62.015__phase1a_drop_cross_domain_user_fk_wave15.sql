-- Phase 1-A wave15: user_id クロスドメインFK撤廃（第七陣 30件）
--
-- 1000万ユーザー耐久DB再構築 Phase 1-A 第十五波。
-- translation / incident / kb / equipment / otp / api / signage / job / timetable /
-- team_module / attendance / committee / error_report / confirmable_notification /
-- webhook / announcement / feedback / schedule_copy_log / ad_report / warning
-- 各ドメインから user ドメイン (users テーブル) への越境 FOREIGN KEY を撤廃し、
-- 参照整合性はアプリケーション層（退会匿名化フロー）で保証する。
--
-- CLAUDE.md §1「クロスドメインFKは作らない」原則に従い撤廃。
-- 物理削除経路は存在しない（論理削除徹底・UserEntity.anonymize() 済）ため
-- CASCADE 撤廃による孤児行の発生は理論上ない。
--
-- 対象テーブル・制約名・カラム（30件）:
--   content_translations              fk_ct_translator        translator_id      ON DELETE SET NULL
--   incident_attachments              fk_ia_created_by        created_by
--   kb_image_uploads                  fk_kbiu_uploader        uploader_id        ON DELETE RESTRICT
--   content_translations              fk_ct_reviewer          reviewer_id        ON DELETE SET NULL
--   equipment_ranking_exclusions      fk_ere_user             excluded_by_user_id
--   otp_challenges                    fk_otp_challenges_user  user_id            ON DELETE CASCADE
--   api_keys                          fk_ak_created_by        created_by
--   signage_access_tokens             fk_sat_created_by       created_by
--   job_contracts                     fk_jc_worker            worker_user_id     ON DELETE RESTRICT
--   timetable_slot_user_notes         fk_tsun_user            user_id            ON DELETE CASCADE
--   job_applications                  fk_ja_decider           decided_by_user_id ON DELETE SET NULL
--   job_contracts                     fk_jc_requester         requester_user_id  ON DELETE RESTRICT
--   timetable_slot_user_note_fields   fk_tsunf_user           user_id            ON DELETE CASCADE
--   team_enabled_modules              fk_team_enabled_modules_user  enabled_by   ON DELETE SET NULL
--   attendance_requirement_evaluations fk_are_student         student_user_id
--   job_qr_tokens                     fk_jqt_issuer           issued_by_user_id  ON DELETE RESTRICT
--   committee_distribution_logs       fk_cdl_created_by       created_by         ON DELETE SET NULL
--   error_report_occurrences          fk_ero_user_id          user_id            ON DELETE SET NULL
--   confirmable_notifications         fk_cn_cancelled_by      cancelled_by       ON DELETE SET NULL
--   translation_assignments           fk_transassign_user     user_id            ON DELETE CASCADE
--   webhook_endpoints                 fk_we_created_by        created_by
--   confirmable_notifications         fk_cn_created_by        created_by         ON DELETE SET NULL
--   job_check_ins                     fk_jci_worker           worker_user_id     ON DELETE RESTRICT
--   job_applications                  fk_ja_applicant         applicant_user_id  ON DELETE CASCADE
--   timetable_slot_user_note_attachments fk_tsuna_user        user_id            ON DELETE CASCADE
--   announcement_feeds                fk_af_author            author_id          ON DELETE SET NULL
--   feedback_submissions              fk_fs_submitted_by      submitted_by       ON DELETE CASCADE
--   schedule_annual_copy_logs         fk_sacl_executed_by     executed_by        ON DELETE SET NULL
--   ad_report_schedules               fk_ad_report_schedules_user  created_by
--   warning_re_reviews                fk_wrr_user             user_id

-- ===== translation ドメイン: content_translations =====
-- translator_id: idx_ct_translator (translator_id) でカバー済み → 追加不要
-- reviewer_id: index なし → 追加
ALTER TABLE content_translations DROP FOREIGN KEY fk_ct_translator;
ALTER TABLE content_translations DROP FOREIGN KEY fk_ct_reviewer;
CREATE INDEX idx_ct_reviewer ON content_translations (reviewer_id);

-- ===== incident ドメイン: incident_attachments =====
-- created_by: index なし → 追加
ALTER TABLE incident_attachments DROP FOREIGN KEY fk_ia_created_by;
CREATE INDEX idx_ia_created_by ON incident_attachments (created_by);

-- ===== knowledge ドメイン: kb_image_uploads =====
-- uploader_id: idx_kbiu_uploader (uploader_id) でカバー済み → 追加不要
ALTER TABLE kb_image_uploads DROP FOREIGN KEY fk_kbiu_uploader;

-- ===== equipment ドメイン: equipment_ranking_exclusions =====
-- excluded_by_user_id: index なし → 追加
ALTER TABLE equipment_ranking_exclusions DROP FOREIGN KEY fk_ere_user;
CREATE INDEX idx_ere_excluded_by_user_id ON equipment_ranking_exclusions (excluded_by_user_id);

-- ===== auth ドメイン: otp_challenges =====
-- user_id: idx_otp_challenges_user_purpose (user_id, purpose) でカバー済み → 追加不要
ALTER TABLE otp_challenges DROP FOREIGN KEY fk_otp_challenges_user;

-- ===== api ドメイン: api_keys =====
-- created_by: index なし → 追加
ALTER TABLE api_keys DROP FOREIGN KEY fk_ak_created_by;
CREATE INDEX idx_ak_created_by ON api_keys (created_by);

-- ===== signage ドメイン: signage_access_tokens =====
-- created_by: index なし → 追加
ALTER TABLE signage_access_tokens DROP FOREIGN KEY fk_sat_created_by;
CREATE INDEX idx_sat_created_by ON signage_access_tokens (created_by);

-- ===== jobmatching ドメイン: job_contracts =====
-- worker_user_id: idx_jc_worker_status (worker_user_id, status) でカバー済み → 追加不要
-- requester_user_id: idx_jc_requester_status (requester_user_id, status) でカバー済み → 追加不要
ALTER TABLE job_contracts DROP FOREIGN KEY fk_jc_worker;
ALTER TABLE job_contracts DROP FOREIGN KEY fk_jc_requester;

-- ===== jobmatching ドメイン: job_applications =====
-- applicant_user_id: idx_ja_applicant_status (applicant_user_id, status) でカバー済み → 追加不要
-- decided_by_user_id: index なし → 追加
ALTER TABLE job_applications DROP FOREIGN KEY fk_ja_applicant;
ALTER TABLE job_applications DROP FOREIGN KEY fk_ja_decider;
CREATE INDEX idx_ja_decided_by_user_id ON job_applications (decided_by_user_id);

-- ===== jobmatching ドメイン: job_qr_tokens =====
-- issued_by_user_id: index なし → 追加
ALTER TABLE job_qr_tokens DROP FOREIGN KEY fk_jqt_issuer;
CREATE INDEX idx_jqt_issued_by_user_id ON job_qr_tokens (issued_by_user_id);

-- ===== jobmatching ドメイン: job_check_ins =====
-- worker_user_id: idx_jci_worker_scanned (worker_user_id, scanned_at) でカバー済み → 追加不要
ALTER TABLE job_check_ins DROP FOREIGN KEY fk_jci_worker;

-- ===== timetable ドメイン: timetable_slot_user_notes =====
-- user_id: idx_tsun_user_slot (user_id, slot_kind, slot_id) でカバー済み → 追加不要
ALTER TABLE timetable_slot_user_notes DROP FOREIGN KEY fk_tsun_user;

-- ===== timetable ドメイン: timetable_slot_user_note_fields =====
-- user_id: idx_tsunf_user_sort (user_id, sort_order) でカバー済み → 追加不要
ALTER TABLE timetable_slot_user_note_fields DROP FOREIGN KEY fk_tsunf_user;

-- ===== timetable ドメイン: timetable_slot_user_note_attachments =====
-- user_id: idx_tsuna_user (user_id) でカバー済み → 追加不要
ALTER TABLE timetable_slot_user_note_attachments DROP FOREIGN KEY fk_tsuna_user;

-- ===== team ドメイン: team_enabled_modules =====
-- enabled_by: index なし → 追加
ALTER TABLE team_enabled_modules DROP FOREIGN KEY fk_team_enabled_modules_user;
CREATE INDEX idx_team_enabled_modules_enabled_by ON team_enabled_modules (enabled_by);

-- ===== attendance ドメイン: attendance_requirement_evaluations =====
-- student_user_id: idx_are_student_time (student_user_id, evaluated_at DESC) でカバー済み → 追加不要
-- fk_are_resolver (resolver_user_id) は今波の対象外（次波で処理）
ALTER TABLE attendance_requirement_evaluations DROP FOREIGN KEY fk_are_student;

-- ===== committee ドメイン: committee_distribution_logs =====
-- created_by: index なし → 追加
ALTER TABLE committee_distribution_logs DROP FOREIGN KEY fk_cdl_created_by;
CREATE INDEX idx_cdl_created_by ON committee_distribution_logs (created_by);

-- ===== error_report ドメイン: error_report_occurrences =====
-- user_id: index なし → 追加（idx_ero_error_report_id_occurred は error_report_id 先頭のため非カバー）
ALTER TABLE error_report_occurrences DROP FOREIGN KEY fk_ero_user_id;
CREATE INDEX idx_ero_user_id ON error_report_occurrences (user_id);

-- ===== notification ドメイン: confirmable_notifications =====
-- created_by: idx_cn_created_by (created_by) でカバー済み → 追加不要
-- cancelled_by: index なし → 追加
ALTER TABLE confirmable_notifications DROP FOREIGN KEY fk_cn_cancelled_by;
ALTER TABLE confirmable_notifications DROP FOREIGN KEY fk_cn_created_by;
CREATE INDEX idx_cn_cancelled_by ON confirmable_notifications (cancelled_by);

-- ===== translation ドメイン: translation_assignments =====
-- user_id: idx_ta_user (user_id) でカバー済み → 追加不要
ALTER TABLE translation_assignments DROP FOREIGN KEY fk_transassign_user;

-- ===== webhook ドメイン: webhook_endpoints =====
-- created_by: index なし → 追加
ALTER TABLE webhook_endpoints DROP FOREIGN KEY fk_we_created_by;
CREATE INDEX idx_we_created_by ON webhook_endpoints (created_by);

-- ===== announcement ドメイン: announcement_feeds =====
-- author_id: idx_af_author (author_id) でカバー済み → 追加不要
ALTER TABLE announcement_feeds DROP FOREIGN KEY fk_af_author;

-- ===== feedback ドメイン: feedback_submissions =====
-- submitted_by: idx_fs_submitted_by (submitted_by) でカバー済み → 追加不要
ALTER TABLE feedback_submissions DROP FOREIGN KEY fk_fs_submitted_by;

-- ===== schedule ドメイン: schedule_annual_copy_logs =====
-- executed_by: index なし → 追加
ALTER TABLE schedule_annual_copy_logs DROP FOREIGN KEY fk_sacl_executed_by;
CREATE INDEX idx_sacl_executed_by ON schedule_annual_copy_logs (executed_by);

-- ===== advertising ドメイン: ad_report_schedules =====
-- created_by: index なし → 追加
ALTER TABLE ad_report_schedules DROP FOREIGN KEY fk_ad_report_schedules_user;
CREATE INDEX idx_ad_report_schedules_created_by ON ad_report_schedules (created_by);

-- ===== admin ドメイン: warning_re_reviews =====
-- user_id: UNIQUE KEY uq_wrr_user_action (user_id, action_id) が先頭列でカバー済み → 追加不要
ALTER TABLE warning_re_reviews DROP FOREIGN KEY fk_wrr_user;
