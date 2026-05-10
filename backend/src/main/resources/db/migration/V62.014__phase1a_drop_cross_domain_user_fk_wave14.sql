-- Phase 1-A wave14: user_id クロスドメインFK 撤廃（第六陣 30件）
--
-- 1000万ユーザー耐久DB再構築 Phase 1-A 第十四波。
-- service_record / receipt / ticket / proxy_vote / shift_assignment_runs /
-- audit_logs / signage / webhook / error_report / confirmable_notification /
-- incident 系の user_id 参照（users テーブルへの越境FK）30 件を撤廃する。
--
-- CLAUDE.md §1「クロスドメインFKは作らない」原則に従い撤廃。
-- users は論理削除（deleted_at）で管理されており、物理削除は発生しない。
-- CASCADE/SET NULL/RESTRICT 撤廃による孤児行の発生は理論上ない。
--
-- 将来のユーザードメイン独立シャーディング・マイクロサービス分割時に
-- FK 境界をまたぐ制約が障壁になるため今のうちに撤廃する。
--
-- ━━━ 対象一覧（30件）━━━
--
--   service_record ドメイン:
--     service_record_templates.fk_srt_user  (created_by, ON DELETE SET NULL)
--
--   receipt ドメイン:
--     receipts.fk_r_voided_by           (voided_by, NULL可)
--     receipts.fk_r_recipient_user      (recipient_user_id)
--     receipts.fk_r_issued_by           (issued_by)
--     receipt_queue.fk_rq_recipient_user (recipient_user_id)
--     receipt_presets.fk_rp_created_by  (created_by)
--     receipt_issuer_settings.fk_ris_default_seal_user (default_seal_user_id, NULL可)
--
--   ticket ドメイン:
--     ticket_products.fk_ticket_prod_created_by (created_by)
--     ticket_payments.fk_tpay_user              (user_id)
--     ticket_payments.fk_tpay_recorded_by       (recorded_by, NULL可)
--     ticket_books.fk_tb_user                   (user_id)
--
--   proxy_vote ドメイン:
--     proxy_vote_motion_comments.fk_pvmc_user   (user_id, ON DELETE RESTRICT)
--     proxy_vote_attachments.fk_pva_uploaded_by (uploaded_by)
--     content_payment_gates.fk_cpg_created_by   (created_by, ON DELETE SET NULL)
--     proxy_vote_sessions.fk_pvs_created_by     (created_by, ON DELETE RESTRICT)
--     proxy_votes.fk_pv_user                    (user_id)
--     proxy_delegations.fk_pd_reviewer          (reviewed_by, NULL可)
--     proxy_delegations.fk_pd_delegator         (delegator_id)
--     proxy_delegations.fk_pd_delegate          (delegate_id, NULL可)
--
--   shift ドメイン:
--     shift_assignment_runs.fk_shift_assignment_runs_triggered_by (triggered_by)
--
--   audit ドメイン:
--     update_audit_logs(=audit_logs).fk_al_user        (user_id, ON DELETE SET NULL)
--     update_audit_logs(=audit_logs).fk_al_target_user (target_user_id, ON DELETE SET NULL)
--
--   signage ドメイン:
--     signage_emergency_messages.fk_sem_sent_by      (sent_by)
--     signage_emergency_messages.fk_sem_dismissed_by (dismissed_by, NULL可)
--     signage_screens.fk_sigscr_created_by           (created_by)
--
--   webhook ドメイン:
--     incoming_webhook_tokens.fk_iwt_created_by (created_by)
--
--   error_report ドメイン:
--     error_report_ai_analyses.fk_eraa_created_by (created_by, ON DELETE SET NULL)
--     error_report_activities.fk_era_actor_id     (actor_id, ON DELETE SET NULL)
--
--   confirmable_notification ドメイン:
--     confirmable_notification_templates.fk_cnt_created_by (created_by, ON DELETE SET NULL)
--
--   incident ドメイン:
--     incident_assignments.fk_ias_user (user_id, NULL可)
--
-- ━━━ index 状況 ━━━
--
-- カバー済み（追加不要）:
--   receipts.recipient_user_id  : INDEX idx_r_recipient(recipient_user_id, issued_at DESC) 既存
--   ticket_payments.user_id     : INDEX idx_tpay_user(user_id, status) 既存
--   ticket_books.user_id        : INDEX idx_tb_user(user_id, team_id, status) 既存
--   proxy_delegations.delegator_id : UNIQUE KEY uq_pd_session_delegator(session_id, delegator_id) 既存
--   proxy_delegations.delegate_id  : INDEX idx_pd_delegate(delegate_id) 既存
--   proxy_votes.user_id            : INDEX idx_pv_user(user_id) 既存
--   audit_logs.user_id             : INDEX idx_audit_logs_user_id(user_id) 既存（V1.011）
--   audit_logs.target_user_id      : INDEX idx_al_target_user_id(target_user_id) 既存（V11.163）
--   incident_assignments.user_id   : INDEX idx_ias_user(user_id) 既存
--
-- index 追加が必要なもの:
--   service_record_templates.created_by
--   receipts.issued_by
--   receipts.voided_by
--   receipt_queue.recipient_user_id
--   receipt_presets.created_by
--   receipt_issuer_settings.default_seal_user_id
--   ticket_products.created_by
--   ticket_payments.recorded_by
--   proxy_vote_motion_comments.user_id
--   proxy_vote_attachments.uploaded_by
--   content_payment_gates.created_by
--   proxy_vote_sessions.created_by
--   proxy_delegations.reviewed_by
--   shift_assignment_runs.triggered_by
--   signage_emergency_messages.sent_by
--   signage_emergency_messages.dismissed_by
--   signage_screens.created_by
--   incoming_webhook_tokens.created_by
--   error_report_ai_analyses.created_by
--   error_report_activities.actor_id
--   confirmable_notification_templates.created_by

-- ===== service_record ドメイン =====

-- service_record_templates.fk_srt_user (created_by, ON DELETE SET NULL)
-- created_by に単独 index なし → 追加
ALTER TABLE service_record_templates DROP FOREIGN KEY fk_srt_user;
CREATE INDEX idx_srt_created_by ON service_record_templates (created_by);

-- ===== receipt ドメイン =====

-- receipts.fk_r_voided_by (voided_by, NULL可)
-- voided_by に index なし → 追加
ALTER TABLE receipts DROP FOREIGN KEY fk_r_voided_by;
CREATE INDEX idx_r_voided_by ON receipts (voided_by);

-- receipts.fk_r_recipient_user (recipient_user_id)
-- idx_r_recipient(recipient_user_id, issued_at DESC) でカバー済 → 追加不要
ALTER TABLE receipts DROP FOREIGN KEY fk_r_recipient_user;

-- receipts.fk_r_issued_by (issued_by)
-- issued_by に index なし → 追加
ALTER TABLE receipts DROP FOREIGN KEY fk_r_issued_by;
CREATE INDEX idx_r_issued_by ON receipts (issued_by);

-- receipt_queue.fk_rq_recipient_user (recipient_user_id)
-- recipient_user_id に単独 index なし → 追加
ALTER TABLE receipt_queue DROP FOREIGN KEY fk_rq_recipient_user;
CREATE INDEX idx_rq_recipient_user_id ON receipt_queue (recipient_user_id);

-- receipt_presets.fk_rp_created_by (created_by)
-- created_by に index なし → 追加
ALTER TABLE receipt_presets DROP FOREIGN KEY fk_rp_created_by;
CREATE INDEX idx_rp_created_by ON receipt_presets (created_by);

-- receipt_issuer_settings.fk_ris_default_seal_user (default_seal_user_id, NULL可)
-- default_seal_user_id に index なし → 追加
ALTER TABLE receipt_issuer_settings DROP FOREIGN KEY fk_ris_default_seal_user;
CREATE INDEX idx_ris_default_seal_user_id ON receipt_issuer_settings (default_seal_user_id);

-- ===== ticket ドメイン =====

-- ticket_products.fk_ticket_prod_created_by (created_by)
-- created_by に index なし → 追加
ALTER TABLE ticket_products DROP FOREIGN KEY fk_ticket_prod_created_by;
CREATE INDEX idx_ticket_products_created_by ON ticket_products (created_by);

-- ticket_payments.fk_tpay_user (user_id)
-- idx_tpay_user(user_id, status) でカバー済 → 追加不要
ALTER TABLE ticket_payments DROP FOREIGN KEY fk_tpay_user;

-- ticket_payments.fk_tpay_recorded_by (recorded_by, NULL可)
-- recorded_by に index なし → 追加
ALTER TABLE ticket_payments DROP FOREIGN KEY fk_tpay_recorded_by;
CREATE INDEX idx_tpay_recorded_by ON ticket_payments (recorded_by);

-- ticket_books.fk_tb_user (user_id)
-- idx_tb_user(user_id, team_id, status) でカバー済 → 追加不要
ALTER TABLE ticket_books DROP FOREIGN KEY fk_tb_user;

-- ===== proxy_vote ドメイン =====

-- proxy_vote_motion_comments.fk_pvmc_user (user_id, ON DELETE RESTRICT)
-- user_id に単独 index なし（idx_pvmc_motion は motion_id のみ）→ 追加
ALTER TABLE proxy_vote_motion_comments DROP FOREIGN KEY fk_pvmc_user;
CREATE INDEX idx_pvmc_user_id ON proxy_vote_motion_comments (user_id);

-- proxy_vote_attachments.fk_pva_uploaded_by (uploaded_by)
-- uploaded_by に index なし → 追加
ALTER TABLE proxy_vote_attachments DROP FOREIGN KEY fk_pva_uploaded_by;
CREATE INDEX idx_pva_uploaded_by ON proxy_vote_attachments (uploaded_by);

-- content_payment_gates.fk_cpg_created_by (created_by, ON DELETE SET NULL)
-- created_by に index なし → 追加
ALTER TABLE content_payment_gates DROP FOREIGN KEY fk_cpg_created_by;
CREATE INDEX idx_cpg_created_by ON content_payment_gates (created_by);

-- proxy_vote_sessions.fk_pvs_created_by (created_by, ON DELETE RESTRICT)
-- created_by に index なし → 追加
ALTER TABLE proxy_vote_sessions DROP FOREIGN KEY fk_pvs_created_by;
CREATE INDEX idx_pvs_created_by ON proxy_vote_sessions (created_by);

-- proxy_votes.fk_pv_user (user_id)
-- idx_pv_user(user_id) でカバー済 → 追加不要
ALTER TABLE proxy_votes DROP FOREIGN KEY fk_pv_user;

-- proxy_delegations.fk_pd_reviewer (reviewed_by, NULL可)
-- reviewed_by に index なし → 追加
ALTER TABLE proxy_delegations DROP FOREIGN KEY fk_pd_reviewer;
CREATE INDEX idx_pd_reviewed_by ON proxy_delegations (reviewed_by);

-- proxy_delegations.fk_pd_delegator (delegator_id)
-- UNIQUE KEY uq_pd_session_delegator(session_id, delegator_id) でカバー済 → 追加不要
ALTER TABLE proxy_delegations DROP FOREIGN KEY fk_pd_delegator;

-- proxy_delegations.fk_pd_delegate (delegate_id, NULL可)
-- idx_pd_delegate(delegate_id) でカバー済 → 追加不要
ALTER TABLE proxy_delegations DROP FOREIGN KEY fk_pd_delegate;

-- ===== shift ドメイン =====

-- shift_assignment_runs.fk_shift_assignment_runs_triggered_by (triggered_by)
-- triggered_by に index なし → 追加
ALTER TABLE shift_assignment_runs DROP FOREIGN KEY fk_shift_assignment_runs_triggered_by;
CREATE INDEX idx_shift_assignment_runs_triggered_by ON shift_assignment_runs (triggered_by);

-- ===== audit ドメイン =====

-- audit_logs.fk_al_user (user_id, ON DELETE SET NULL)
-- idx_audit_logs_user_id(user_id) でカバー済（V1.011）→ 追加不要
ALTER TABLE audit_logs DROP FOREIGN KEY fk_al_user;

-- audit_logs.fk_al_target_user (target_user_id, ON DELETE SET NULL)
-- idx_al_target_user_id(target_user_id) でカバー済（V11.163）→ 追加不要
ALTER TABLE audit_logs DROP FOREIGN KEY fk_al_target_user;

-- ===== signage ドメイン =====

-- signage_emergency_messages.fk_sem_sent_by (sent_by)
-- sent_by に index なし → 追加
ALTER TABLE signage_emergency_messages DROP FOREIGN KEY fk_sem_sent_by;
CREATE INDEX idx_sem_sent_by ON signage_emergency_messages (sent_by);

-- signage_emergency_messages.fk_sem_dismissed_by (dismissed_by, NULL可)
-- dismissed_by に index なし → 追加
ALTER TABLE signage_emergency_messages DROP FOREIGN KEY fk_sem_dismissed_by;
CREATE INDEX idx_sem_dismissed_by ON signage_emergency_messages (dismissed_by);

-- signage_screens.fk_sigscr_created_by (created_by)
-- created_by に index なし → 追加
ALTER TABLE signage_screens DROP FOREIGN KEY fk_sigscr_created_by;
CREATE INDEX idx_sigscr_created_by ON signage_screens (created_by);

-- ===== webhook ドメイン =====

-- incoming_webhook_tokens.fk_iwt_created_by (created_by)
-- created_by に index なし → 追加
ALTER TABLE incoming_webhook_tokens DROP FOREIGN KEY fk_iwt_created_by;
CREATE INDEX idx_iwt_created_by ON incoming_webhook_tokens (created_by);

-- ===== error_report ドメイン =====

-- error_report_ai_analyses.fk_eraa_created_by (created_by, ON DELETE SET NULL)
-- created_by に index なし → 追加
ALTER TABLE error_report_ai_analyses DROP FOREIGN KEY fk_eraa_created_by;
CREATE INDEX idx_eraa_created_by ON error_report_ai_analyses (created_by);

-- error_report_activities.fk_era_actor_id (actor_id, ON DELETE SET NULL)
-- actor_id に index なし → 追加
ALTER TABLE error_report_activities DROP FOREIGN KEY fk_era_actor_id;
CREATE INDEX idx_era_actor_id ON error_report_activities (actor_id);

-- ===== confirmable_notification ドメイン =====

-- confirmable_notification_templates.fk_cnt_created_by (created_by, ON DELETE SET NULL)
-- created_by に index なし → 追加
ALTER TABLE confirmable_notification_templates DROP FOREIGN KEY fk_cnt_created_by;
CREATE INDEX idx_cnt_created_by ON confirmable_notification_templates (created_by);

-- ===== incident ドメイン =====

-- incident_assignments.fk_ias_user (user_id, NULL可)
-- idx_ias_user(user_id) でカバー済 → 追加不要
ALTER TABLE incident_assignments DROP FOREIGN KEY fk_ias_user;
