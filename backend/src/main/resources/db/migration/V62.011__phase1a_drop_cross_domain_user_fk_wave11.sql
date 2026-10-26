-- Phase 1-A wave11: user_id クロスドメインFK撤廃（chat/timeline/bulletin/seal/shift系 30件）
--
-- 1000万ユーザー耐久DB再構築 Phase 1-A 第十一波。
-- wave1〜10（V62.001〜010）に続き、chat ドメイン・timeline ドメイン・
-- bulletin ドメイン・seal ドメイン・shift ドメイン・activity ドメイン・
-- team ドメイン・survey ドメイン・storage ドメイン等から
-- user ドメイン (users テーブル) への越境 FOREIGN KEY 30件を撤廃する。
--
-- CLAUDE.md §1「クロスドメインFKは作らない」原則に従い撤廃。
-- users は論理削除（deleted_at）・退会時匿名化（UserEntity.anonymize()）で管理されており、
-- 物理削除は発生しない。CASCADE 撤廃による孤児行の発生は理論上ない。
--
-- 対象テーブル一覧（30件）:
--
--   activity ドメイン:
--     activity_results.fk_ar_created_by          (created_by,    ON DELETE SET NULL)
--
--   photo ドメイン:
--     photos.fk_ph_uploaded_by                   (uploaded_by,   ON DELETE SET NULL)
--
--   membership ドメイン:
--     memberships.fk_memberships_invited_by       (invited_by,    ON DELETE SET NULL)
--     memberships.fk_memberships_user             (user_id,       ON DELETE SET NULL)
--
--   team ドメイン:
--     team_pages.fk_tp_created_by                (created_by,    ON DELETE SET NULL)
--     member_profiles.fk_mp_user                 (user_id,       ON DELETE SET NULL)
--
--   chat ドメイン:
--     chat_channels.fk_channel_creator           (created_by,    ON DELETE SET NULL)
--     chat_channel_members.fk_member_user        (user_id,       ON DELETE CASCADE)
--     chat_message_reactions.fk_reaction_user    (user_id,       ON DELETE CASCADE)
--     chat_message_bookmarks.fk_bookmark_user    (user_id,       ON DELETE CASCADE)
--     chat_messages.fk_msg_sender                (sender_id,     ON DELETE SET NULL)
--
--   timeline ドメイン:
--     timeline_poll_votes.fk_poll_votes_user     (user_id,       ON DELETE CASCADE)
--     timeline_posts.fk_timeline_posts_user      (user_id,       ON DELETE CASCADE)
--     timeline_post_reactions.fk_post_reactions_user (user_id,   ON DELETE CASCADE)
--
--   moderation ドメイン:
--     content_reports.fk_content_reports_reviewer (reviewed_by,  ON DELETE SET NULL)
--     user_mutes.fk_user_mutes_user              (user_id,       ON DELETE CASCADE)
--
--   bulletin ドメイン:
--     bulletin_reactions.fk_bulletin_reactions_user (user_id,    ON DELETE CASCADE)
--     bulletin_categories.fk_bulletin_categories_created_by (created_by, ON DELETE SET NULL)
--     bulletin_attachments.fk_bulletin_attachments_created_by (created_by, ON DELETE SET NULL)
--
--   seal ドメイン:
--     seal_stamp_logs.fk_seal_stamp_logs_user    (user_id,       ON DELETE CASCADE)
--     seal_scope_defaults.fk_seal_scope_defaults_user (user_id,  ON DELETE CASCADE)
--     electronic_seals.fk_electronic_seals_user  (user_id,       ON DELETE CASCADE)
--
--   shift ドメイン:
--     extend_shift_swap_requests_open_call → shift_swap_requests.fk_shift_swap_requests_target_user  (target_user_id, 制約なし)
--     extend_shift_swap_requests_open_call → shift_swap_requests.fk_shift_swap_requests_claimed_by   (claimed_by,    制約なし)
--     shift_assignment_runs.fk_shift_assignment_runs_visual_review (visual_review_confirmed_by, 制約なし)
--     shift_change_requests.fk_shift_change_requests_requested_by (requested_by, 制約なし)
--     shift_change_requests.fk_shift_change_requests_reviewer     (reviewer_id,  制約なし)
--
--   survey ドメイン:
--     survey_responses.fk_survey_responses_user  (user_id,       ON DELETE CASCADE)
--     survey_result_viewers.fk_survey_result_viewers_user (user_id, ON DELETE CASCADE)
--
--   storage ドメイン:
--     shared_files.fk_shared_files_created       (created_by,    ON DELETE SET NULL)

-- =============================================================================
-- activity ドメイン（1件）
-- =============================================================================

-- activity_results.fk_ar_created_by (ON DELETE SET NULL → 撤廃, created_by idx 追加)
-- idx_ar_scope / idx_ar_template 等はあるが created_by 単独索引なし
ALTER TABLE activity_results
    DROP FOREIGN KEY fk_ar_created_by;
CREATE INDEX idx_ar_created_by ON activity_results (created_by);

-- =============================================================================
-- photo ドメイン（1件）
-- =============================================================================

-- photos.fk_ph_uploaded_by (ON DELETE SET NULL → 撤廃, idx_ph_uploaded_by 既存)
ALTER TABLE photos
    DROP FOREIGN KEY fk_ph_uploaded_by;

-- =============================================================================
-- membership ドメイン（2件）
-- =============================================================================

-- memberships.fk_memberships_invited_by (ON DELETE SET NULL → 撤廃, invited_by idx 追加)
-- idx_memberships_user(user_id, left_at) は既存だが invited_by 単独索引なし
ALTER TABLE memberships
    DROP FOREIGN KEY fk_memberships_invited_by;
CREATE INDEX idx_memberships_invited_by ON memberships (invited_by);

-- memberships.fk_memberships_user (ON DELETE SET NULL → 撤廃)
-- idx_memberships_user(user_id, left_at) 既存 → 追加不要
ALTER TABLE memberships
    DROP FOREIGN KEY fk_memberships_user;

-- =============================================================================
-- team ドメイン（2件）
-- =============================================================================

-- team_pages.fk_tp_created_by (ON DELETE SET NULL → 撤廃, created_by idx 追加)
-- uq_tp_slug_team / idx_tp_team_status 等はあるが created_by 単独索引なし
ALTER TABLE team_pages
    DROP FOREIGN KEY fk_tp_created_by;
CREATE INDEX idx_tp_created_by ON team_pages (created_by);

-- member_profiles.fk_mp_user (ON DELETE SET NULL → 撤廃, idx_mp_user 既存)
ALTER TABLE member_profiles
    DROP FOREIGN KEY fk_mp_user;

-- =============================================================================
-- chat ドメイン（5件）
-- =============================================================================

-- chat_channels.fk_channel_creator (ON DELETE SET NULL → 撤廃, created_by idx 追加)
-- idx_channel_team / idx_channel_org はあるが created_by(chat_channels.created_by) 単独索引なし
ALTER TABLE chat_channels
    DROP FOREIGN KEY fk_channel_creator;
CREATE INDEX idx_chat_channels_created_by ON chat_channels (created_by);

-- chat_channel_members.fk_member_user (ON DELETE CASCADE → 撤廃, user_id idx 追加)
-- uk_channel_user(channel_id, user_id) の第2列 → user_id 単独での逆引き検索用索引追加
ALTER TABLE chat_channel_members
    DROP FOREIGN KEY fk_member_user;
CREATE INDEX idx_chat_channel_members_user_id ON chat_channel_members (user_id);

-- chat_message_reactions.fk_reaction_user (ON DELETE CASCADE → 撤廃, user_id idx 追加)
-- uk_reaction_user_emoji(message_id, user_id, emoji) の第2列 → user_id 単独索引追加
ALTER TABLE chat_message_reactions
    DROP FOREIGN KEY fk_reaction_user;
CREATE INDEX idx_chat_message_reactions_user_id ON chat_message_reactions (user_id);

-- chat_message_bookmarks.fk_bookmark_user (ON DELETE CASCADE → 撤廃)
-- uk_bookmark_user_message(user_id, message_id) の先頭列 → 追加不要
ALTER TABLE chat_message_bookmarks
    DROP FOREIGN KEY fk_bookmark_user;

-- chat_messages.fk_msg_sender (ON DELETE SET NULL → 撤廃)
-- idx_msg_sender_created(sender_id, created_at DESC) 既存 → 追加不要
ALTER TABLE chat_messages
    DROP FOREIGN KEY fk_msg_sender;

-- =============================================================================
-- timeline ドメイン（3件）
-- =============================================================================

-- timeline_poll_votes.fk_poll_votes_user (ON DELETE CASCADE → 撤廃, user_id idx 追加)
-- uk_poll_votes(timeline_poll_id, user_id) の第2列 → user_id 単独索引追加
ALTER TABLE timeline_poll_votes
    DROP FOREIGN KEY fk_poll_votes_user;
CREATE INDEX idx_timeline_poll_votes_user_id ON timeline_poll_votes (user_id);

-- timeline_posts.fk_timeline_posts_user (ON DELETE CASCADE → 撤廃)
-- idx_timeline_posts_user(user_id, deleted_at, created_at DESC) 既存 → 追加不要
ALTER TABLE timeline_posts
    DROP FOREIGN KEY fk_timeline_posts_user;

-- timeline_post_reactions.fk_post_reactions_user (ON DELETE CASCADE → 撤廃, user_id idx 追加)
-- uk_post_reactions(timeline_post_id, user_id, emoji) の第2列 → user_id 単独索引追加
ALTER TABLE timeline_post_reactions
    DROP FOREIGN KEY fk_post_reactions_user;
CREATE INDEX idx_timeline_post_reactions_user_id ON timeline_post_reactions (user_id);

-- =============================================================================
-- moderation ドメイン（2件）
-- =============================================================================

-- content_reports.fk_content_reports_reviewer (ON DELETE SET NULL → 撤廃, reviewed_by idx 追加)
-- idx_content_reports_status / idx_content_reports_target はあるが reviewed_by 単独索引なし
ALTER TABLE content_reports
    DROP FOREIGN KEY fk_content_reports_reviewer;
CREATE INDEX idx_content_reports_reviewed_by ON content_reports (reviewed_by);

-- user_mutes.fk_user_mutes_user (ON DELETE CASCADE → 撤廃)
-- uk_user_mutes(user_id, muted_type, muted_id) の先頭列 → 追加不要
ALTER TABLE user_mutes
    DROP FOREIGN KEY fk_user_mutes_user;

-- =============================================================================
-- bulletin ドメイン（3件）
-- =============================================================================

-- bulletin_reactions.fk_bulletin_reactions_user (ON DELETE CASCADE → 撤廃, user_id idx 追加)
-- uk_bulletin_reactions_target_user_emoji(target_type, target_id, user_id, emoji) の第3列
-- → user_id 単独での逆引き検索用索引追加
ALTER TABLE bulletin_reactions
    DROP FOREIGN KEY fk_bulletin_reactions_user;
CREATE INDEX idx_bulletin_reactions_user_id ON bulletin_reactions (user_id);

-- bulletin_categories.fk_bulletin_categories_created_by (ON DELETE SET NULL → 撤廃, created_by idx 追加)
-- created_by 単独索引なし
ALTER TABLE bulletin_categories
    DROP FOREIGN KEY fk_bulletin_categories_created_by;
CREATE INDEX idx_bulletin_categories_created_by ON bulletin_categories (created_by);

-- bulletin_attachments.fk_bulletin_attachments_created_by (ON DELETE SET NULL → 撤廃, created_by idx 追加)
-- created_by 単独索引なし
ALTER TABLE bulletin_attachments
    DROP FOREIGN KEY fk_bulletin_attachments_created_by;
CREATE INDEX idx_bulletin_attachments_created_by ON bulletin_attachments (created_by);

-- =============================================================================
-- seal ドメイン（3件）
-- =============================================================================

-- seal_stamp_logs.fk_seal_stamp_logs_user (ON DELETE CASCADE → 撤廃)
-- idx_seal_stamp_logs_user_stamped(user_id, stamped_at DESC) 既存 → 追加不要
ALTER TABLE seal_stamp_logs
    DROP FOREIGN KEY fk_seal_stamp_logs_user;

-- seal_scope_defaults.fk_seal_scope_defaults_user (ON DELETE CASCADE → 撤廃)
-- uk_seal_scope_defaults_user_scope(user_id, scope_type, scope_id) の先頭列 → 追加不要
ALTER TABLE seal_scope_defaults
    DROP FOREIGN KEY fk_seal_scope_defaults_user;

-- electronic_seals.fk_electronic_seals_user (ON DELETE CASCADE → 撤廃)
-- uk_electronic_seals_user_variant(user_id, variant) の先頭列 → 追加不要
ALTER TABLE electronic_seals
    DROP FOREIGN KEY fk_electronic_seals_user;

-- =============================================================================
-- shift ドメイン（5件）
-- =============================================================================

-- shift_swap_requests.fk_shift_swap_requests_target_user (制約なし → 撤廃, target_user_id idx 追加)
-- V3.144__extend_shift_swap_requests_open_call.sql で追加された FK
-- target_user_id 単独索引なし → 追加
ALTER TABLE shift_swap_requests
    DROP FOREIGN KEY fk_shift_swap_requests_target_user;
CREATE INDEX idx_shift_swap_requests_target_user_id ON shift_swap_requests (target_user_id);

-- shift_swap_requests.fk_shift_swap_requests_claimed_by (制約なし → 撤廃)
-- V3.144 で追加。idx_shift_swap_requests_claimed_by(claimed_by) 既存 → 追加不要
ALTER TABLE shift_swap_requests
    DROP FOREIGN KEY fk_shift_swap_requests_claimed_by;

-- shift_assignment_runs.fk_shift_assignment_runs_visual_review (制約なし → 撤廃, idx 追加)
-- visual_review_confirmed_by 単独索引なし
ALTER TABLE shift_assignment_runs
    DROP FOREIGN KEY fk_shift_assignment_runs_visual_review;
CREATE INDEX idx_shift_assignment_runs_visual_review_by ON shift_assignment_runs (visual_review_confirmed_by);

-- shift_change_requests.fk_shift_change_requests_requested_by (制約なし → 撤廃)
-- idx_shift_change_requests_requested_by(requested_by) 既存 → 追加不要
ALTER TABLE shift_change_requests
    DROP FOREIGN KEY fk_shift_change_requests_requested_by;

-- shift_change_requests.fk_shift_change_requests_reviewer (制約なし → 撤廃, reviewer_id idx 追加)
-- reviewer_id 単独索引なし
ALTER TABLE shift_change_requests
    DROP FOREIGN KEY fk_shift_change_requests_reviewer;
CREATE INDEX idx_shift_change_requests_reviewer_id ON shift_change_requests (reviewer_id);

-- =============================================================================
-- survey ドメイン（2件）
-- =============================================================================

-- survey_responses.fk_survey_responses_user (ON DELETE CASCADE → 撤廃, user_id idx 追加)
-- idx_survey_responses_survey_user(survey_id, user_id) の第2列 → user_id 単独索引追加
ALTER TABLE survey_responses
    DROP FOREIGN KEY fk_survey_responses_user;
CREATE INDEX idx_survey_responses_user_id ON survey_responses (user_id);

-- survey_result_viewers.fk_survey_result_viewers_user (ON DELETE CASCADE → 撤廃, user_id idx 追加)
-- uk_survey_result_viewers_survey_user(survey_id, user_id) の第2列 → user_id 単独索引追加
ALTER TABLE survey_result_viewers
    DROP FOREIGN KEY fk_survey_result_viewers_user;
CREATE INDEX idx_survey_result_viewers_user_id ON survey_result_viewers (user_id);

-- =============================================================================
-- storage ドメイン（1件）
-- =============================================================================

-- shared_files.fk_shared_files_created (ON DELETE SET NULL → 撤廃, created_by idx 追加)
-- created_by 単独索引なし
ALTER TABLE shared_files
    DROP FOREIGN KEY fk_shared_files_created;
CREATE INDEX idx_shared_files_created_by ON shared_files (created_by);
