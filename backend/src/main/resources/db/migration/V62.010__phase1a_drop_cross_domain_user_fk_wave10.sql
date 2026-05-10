-- Phase 1-A wave10: user_id クロスドメインFK撤廃（workflow/activity/blog/form/property系 30件）
--
-- 1000万ユーザー耐久DB再構築 Phase 1-A 第十波。
-- wave1〜9（V62.001〜009）に続き、workflow ドメイン・activity ドメイン・blog ドメイン・
-- form ドメイン・storage ドメイン・property ドメイン等から
-- user ドメイン (users テーブル) への越境 FOREIGN KEY 30件を撤廃する。
--
-- CLAUDE.md §1「クロスドメインFKは作らない」原則に従い撤廃。
-- users は論理削除（deleted_at）・退会時匿名化（UserEntity.anonymize()）で管理されており、
-- 物理削除は発生しない。CASCADE 撤廃による孤児行の発生は理論上ない。
--
-- 対象テーブル一覧（30件）:
--
--   activity ドメイン:
--     activity_templates.fk_at_created_by          (created_by,  ON DELETE RESTRICT)
--     activity_participants.fk_ap_user              (user_id,     ON DELETE CASCADE)
--     activity_comments.fk_ac_user                 (user_id,     ON DELETE SET NULL)
--
--   workflow ドメイン:
--     workflow_request_comments.fk_wf_request_comments_user     (user_id,       ON DELETE SET NULL)
--     workflow_request_attachments.fk_wf_request_attachments_uploaded_by (uploaded_by, ON DELETE SET NULL)
--     workflow_requests.fk_workflow_requests_requested_by       (requested_by,  ON DELETE SET NULL)
--     workflow_request_approvers.fk_wf_request_approvers_user   (approver_user_id, ON DELETE CASCADE)
--     workflow_templates.fk_workflow_templates_created_by       (created_by,    ON DELETE SET NULL)
--
--   blog ドメイン:
--     blog_post_revisions.fk_bpr_editor            (editor_id,   ON DELETE SET NULL)
--     blog_post_shares.fk_bps_shared_by            (shared_by,   ON DELETE SET NULL)
--     user_blog_settings.fk_ubs_user               (user_id,     ON DELETE CASCADE)
--     blog_image_uploads.fk_biu_uploader           (uploader_id, ON DELETE SET NULL)
--
--   form ドメイン:
--     survey_targets.fk_survey_targets_user        (user_id,     ON DELETE CASCADE)
--     form_submissions.fk_form_submissions_submitted_by (submitted_by, ON DELETE SET NULL)
--     form_templates.fk_form_templates_created_by  (created_by,  ON DELETE SET NULL)
--     system_form_presets.fk_system_form_presets_created_by (created_by, ON DELETE SET NULL)
--
--   storage ドメイン:
--     shared_file_stars.fk_file_stars_user         (user_id,     ON DELETE CASCADE)
--     shared_file_links.fk_file_links_created      (created_by,  ON DELETE SET NULL)
--     shared_file_comments.fk_file_comments_user   (user_id,     ON DELETE SET NULL)
--     shared_file_tags.fk_file_tags_user           (user_id,     ON DELETE CASCADE)
--
--   membership ドメイン:
--     member_positions.fk_member_positions_assigned_by (assigned_by, ON DELETE SET NULL)
--
--   property/disclosure ドメイン:
--     property_work_history_views.fk_pwhv_user     (user_id,     ON DELETE CASCADE)
--     disclosure_form_drafts.fk_dfd_created_by     (created_by,  ON DELETE RESTRICT)
--     disclosure_form_drafts.fk_dfd_updated_by     (updated_by,  ON DELETE SET NULL)
--     photo_albums.fk_pa_created_by                (created_by,  ON DELETE SET NULL)
--     disclosure_exports.fk_de_requester           (requester_user_id, ON DELETE RESTRICT)
--     disclosure_form_templates.fk_dft_created_by  (created_by,  ON DELETE SET NULL)
--     property_work_packages.fk_pwp_created_by     (created_by,  ON DELETE RESTRICT)
--     vendors.fk_vendors_created_by                (created_by,  ON DELETE RESTRICT)
--     property_work_documents.fk_pwd_created_by    (created_by,  ON DELETE RESTRICT)

-- =============================================================================
-- activity ドメイン（3件）
-- =============================================================================

-- activity_templates.fk_at_created_by (ON DELETE RESTRICT → 撤廃, created_by への idx 追加)
ALTER TABLE activity_templates
    DROP FOREIGN KEY fk_at_created_by;
CREATE INDEX idx_at_created_by ON activity_templates (created_by);

-- activity_participants.fk_ap_user (ON DELETE CASCADE → 撤廃, idx_ap_user 既存)
ALTER TABLE activity_participants
    DROP FOREIGN KEY fk_ap_user;

-- activity_comments.fk_ac_user (ON DELETE SET NULL → 撤廃, user_id への idx 追加)
ALTER TABLE activity_comments
    DROP FOREIGN KEY fk_ac_user;
CREATE INDEX idx_ac_user ON activity_comments (user_id);

-- =============================================================================
-- workflow ドメイン（5件）
-- =============================================================================

-- workflow_request_comments.fk_wf_request_comments_user (ON DELETE SET NULL → 撤廃, idx_wf_request_comments_user 既存)
ALTER TABLE workflow_request_comments
    DROP FOREIGN KEY fk_wf_request_comments_user;

-- workflow_request_attachments.fk_wf_request_attachments_uploaded_by (ON DELETE SET NULL → 撤廃, idx_wf_request_attachments_uploaded_by 既存)
ALTER TABLE workflow_request_attachments
    DROP FOREIGN KEY fk_wf_request_attachments_uploaded_by;

-- workflow_requests.fk_workflow_requests_requested_by (ON DELETE SET NULL → 撤廃, idx_workflow_requests_requested_by 既存)
ALTER TABLE workflow_requests
    DROP FOREIGN KEY fk_workflow_requests_requested_by;

-- workflow_request_approvers.fk_wf_request_approvers_user (ON DELETE CASCADE → 撤廃, idx_wf_request_approvers_user 既存)
ALTER TABLE workflow_request_approvers
    DROP FOREIGN KEY fk_wf_request_approvers_user;

-- workflow_templates.fk_workflow_templates_created_by (ON DELETE SET NULL → 撤廃, idx_workflow_templates_created_by 既存)
ALTER TABLE workflow_templates
    DROP FOREIGN KEY fk_workflow_templates_created_by;

-- =============================================================================
-- blog ドメイン（4件）
-- =============================================================================

-- blog_post_revisions.fk_bpr_editor (ON DELETE SET NULL → 撤廃, editor_id への idx 追加)
ALTER TABLE blog_post_revisions
    DROP FOREIGN KEY fk_bpr_editor;
CREATE INDEX idx_bpr_editor ON blog_post_revisions (editor_id);

-- blog_post_shares.fk_bps_shared_by (ON DELETE SET NULL → 撤廃, shared_by への idx 追加)
ALTER TABLE blog_post_shares
    DROP FOREIGN KEY fk_bps_shared_by;
CREATE INDEX idx_bps_shared_by ON blog_post_shares (shared_by);

-- user_blog_settings.fk_ubs_user (ON DELETE CASCADE → 撤廃, uq_ubs_user(user_id) 既存)
ALTER TABLE user_blog_settings
    DROP FOREIGN KEY fk_ubs_user;

-- blog_image_uploads.fk_biu_uploader (ON DELETE SET NULL → 撤廃, uploader_id への idx 追加)
ALTER TABLE blog_image_uploads
    DROP FOREIGN KEY fk_biu_uploader;
CREATE INDEX idx_biu_uploader ON blog_image_uploads (uploader_id);

-- =============================================================================
-- form ドメイン（4件）
-- =============================================================================

-- survey_targets.fk_survey_targets_user (ON DELETE CASCADE → 撤廃, uk_survey_targets_survey_user(survey_id, user_id) 既存)
ALTER TABLE survey_targets
    DROP FOREIGN KEY fk_survey_targets_user;

-- form_submissions.fk_form_submissions_submitted_by (ON DELETE SET NULL → 撤廃, idx_form_submissions_submitted_by 既存)
ALTER TABLE form_submissions
    DROP FOREIGN KEY fk_form_submissions_submitted_by;

-- form_templates.fk_form_templates_created_by (ON DELETE SET NULL → 撤廃, created_by への idx 追加)
ALTER TABLE form_templates
    DROP FOREIGN KEY fk_form_templates_created_by;
CREATE INDEX idx_form_templates_created_by ON form_templates (created_by);

-- system_form_presets.fk_system_form_presets_created_by (ON DELETE SET NULL → 撤廃, created_by への idx 追加)
ALTER TABLE system_form_presets
    DROP FOREIGN KEY fk_system_form_presets_created_by;
CREATE INDEX idx_system_form_presets_created_by ON system_form_presets (created_by);

-- =============================================================================
-- storage ドメイン（4件）
-- =============================================================================

-- shared_file_stars.fk_file_stars_user (ON DELETE CASCADE → 撤廃, uk_file_stars_file_user(file_id, user_id) 既存)
ALTER TABLE shared_file_stars
    DROP FOREIGN KEY fk_file_stars_user;

-- shared_file_links.fk_file_links_created (ON DELETE SET NULL → 撤廃, created_by への idx 追加)
ALTER TABLE shared_file_links
    DROP FOREIGN KEY fk_file_links_created;
CREATE INDEX idx_file_links_created_by ON shared_file_links (created_by);

-- shared_file_comments.fk_file_comments_user (ON DELETE SET NULL → 撤廃, user_id への idx 追加)
ALTER TABLE shared_file_comments
    DROP FOREIGN KEY fk_file_comments_user;
CREATE INDEX idx_file_comments_user ON shared_file_comments (user_id);

-- shared_file_tags.fk_file_tags_user (ON DELETE CASCADE → 撤廃, uk_file_tags_file_tag_user(file_id, tag_name, user_id) 既存)
ALTER TABLE shared_file_tags
    DROP FOREIGN KEY fk_file_tags_user;

-- =============================================================================
-- membership ドメイン（1件）
-- =============================================================================

-- member_positions.fk_member_positions_assigned_by (ON DELETE SET NULL → 撤廃, assigned_by への idx 追加)
ALTER TABLE member_positions
    DROP FOREIGN KEY fk_member_positions_assigned_by;
CREATE INDEX idx_member_positions_assigned_by ON member_positions (assigned_by);

-- =============================================================================
-- property / disclosure ドメイン（9件）
-- =============================================================================

-- property_work_history_views.fk_pwhv_user (ON DELETE CASCADE → 撤廃, idx_pwhv_user_time(user_id, viewed_at) 既存)
ALTER TABLE property_work_history_views
    DROP FOREIGN KEY fk_pwhv_user;

-- disclosure_form_drafts.fk_dfd_created_by (ON DELETE RESTRICT → 撤廃, created_by への idx 追加)
ALTER TABLE disclosure_form_drafts
    DROP FOREIGN KEY fk_dfd_created_by;
CREATE INDEX idx_dfd_created_by ON disclosure_form_drafts (created_by);

-- disclosure_form_drafts.fk_dfd_updated_by (ON DELETE SET NULL → 撤廃, updated_by への idx 追加)
ALTER TABLE disclosure_form_drafts
    DROP FOREIGN KEY fk_dfd_updated_by;
CREATE INDEX idx_dfd_updated_by ON disclosure_form_drafts (updated_by);

-- photo_albums.fk_pa_created_by (ON DELETE SET NULL → 撤廃, idx_pa_created_by 既存)
ALTER TABLE photo_albums
    DROP FOREIGN KEY fk_pa_created_by;

-- disclosure_exports.fk_de_requester (ON DELETE RESTRICT → 撤廃, idx_de_requester(requester_user_id, created_at) 既存)
ALTER TABLE disclosure_exports
    DROP FOREIGN KEY fk_de_requester;

-- disclosure_form_templates.fk_dft_created_by (ON DELETE SET NULL → 撤廃, created_by への idx 追加)
ALTER TABLE disclosure_form_templates
    DROP FOREIGN KEY fk_dft_created_by;
CREATE INDEX idx_dft_created_by ON disclosure_form_templates (created_by);

-- property_work_packages.fk_pwp_created_by (ON DELETE RESTRICT → 撤廃, created_by への idx 追加)
ALTER TABLE property_work_packages
    DROP FOREIGN KEY fk_pwp_created_by;
CREATE INDEX idx_pwp_created_by ON property_work_packages (created_by);

-- vendors.fk_vendors_created_by (ON DELETE RESTRICT → 撤廃, created_by への idx 追加)
ALTER TABLE vendors
    DROP FOREIGN KEY fk_vendors_created_by;
CREATE INDEX idx_vendors_created_by ON vendors (created_by);

-- property_work_documents.fk_pwd_created_by (ON DELETE RESTRICT → 撤廃, created_by への idx 追加)
ALTER TABLE property_work_documents
    DROP FOREIGN KEY fk_pwd_created_by;
CREATE INDEX idx_pwd_created_by ON property_work_documents (created_by);
