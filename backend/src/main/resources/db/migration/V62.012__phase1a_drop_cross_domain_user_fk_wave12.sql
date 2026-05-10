-- Phase 1-A wave12: user_id クロスドメインFK 撤廃（第四陣 30件）
--
-- 1000万ユーザー耐久DB再構築 Phase 1-A 第十二波。
-- circulation / bulletin / mentions / corkboard / sns / tournament /
-- user_line / direct_mail / ticket / line_bot / committee / friend_forward 系
-- の user_id 参照（users テーブルへの越境FK）30 件を撤廃する。
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
--   circulation ドメイン:
--     circulation_recipients.fk_circulation_recipients_user  (ON DELETE CASCADE)
--     circulation_documents.fk_circulation_documents_created_by  (ON DELETE RESTRICT)
--     circulation_comments.fk_circulation_comments_user  (ON DELETE CASCADE)
--
--   bulletin ドメイン:
--     bulletin_replies.fk_bulletin_replies_author  (ON DELETE SET NULL)
--     bulletin_read_status.fk_bulletin_read_status_user  (ON DELETE CASCADE)
--     bulletin_threads.fk_bulletin_threads_author  (ON DELETE SET NULL)
--
--   mention ドメイン:
--     mentions.fk_mention_user  (mentioned_user_id, ON DELETE CASCADE)
--     mentions.fk_mention_by    (mentioned_by_id,   ON DELETE CASCADE)
--
--   corkboard ドメイン:
--     corkboards.fk_cb_owner  (owner_id, ON DELETE CASCADE)
--     corkboard_cards.fk_cc_created_by  (created_by)
--
--   sns ドメイン:
--     sns_feed_configs.fk_sfc_configured_by  (configured_by)
--
--   tournament ドメイン:
--     tournament_promotion_records.fk_tpr_executed_by  (executed_by)
--     tournament_templates.fk_tt_created_by  (created_by)
--     tournaments.fk_t_created_by  (created_by)
--     tournament_individual_rankings.fk_tir_user  (user_id, ON DELETE CASCADE)
--     tournament_match_rosters.fk_tmr_user  (user_id, ON DELETE CASCADE)
--     tournament_match_player_stats.fk_tmps_user  (user_id, ON DELETE CASCADE)
--
--   line ドメイン:
--     user_line_connections.fk_ulc_user_id  (user_id)
--     line_bot_configs.fk_lbc_configured_by  (configured_by)
--
--   direct_mail ドメイン:
--     direct_mail_templates.fk_dmt_created_by  (created_by)
--     direct_mail_image_uploads.fk_dmiu_uploaded_by  (uploaded_by)
--     direct_mail_logs.fk_dml_sender  (sender_id)
--     direct_mail_recipients.fk_dmr_user  (user_id)
--
--   ticket ドメイン:
--     ticket_consumptions.fk_tc_voided_by  (voided_by, NULL可)
--     ticket_consumptions.fk_tc_consumed_by  (consumed_by)
--     ticket_books.fk_tb_issued_by  (issued_by, NULL可)
--
--   committee ドメイン:
--     committee_members.fk_cm_user  (user_id, ON DELETE CASCADE)
--     committee_members.fk_cm_invited_by  (invited_by, ON DELETE SET NULL)
--
--   social ドメイン:
--     friend_content_forwards.fk_fcf_forwarded_by  (forwarded_by, ON DELETE RESTRICT)
--     friend_content_forwards.fk_fcf_revoked_by  (revoked_by, ON DELETE SET NULL)
--
-- ━━━ index 状況 ━━━
--
-- カバー済み（追加不要）:
--   circulation_recipients     : UNIQUE KEY uk_circulation_recipients_doc_user(document_id, user_id) — user_idは第2列だが一意制約でカバー
--   bulletin_read_status       : UNIQUE KEY uk_bulletin_read_status_thread_user(thread_id, user_id) — 同上
--   mentions.mentioned_user_id : INDEX idx_mention_user_read(mentioned_user_id, is_read, created_at DESC) 既存
--   corkboards.owner_id        : INDEX idx_corkboards_personal(owner_id, deleted_at) 既存
--   direct_mail_logs.sender_id : INDEX idx_dml_sender(sender_id) 既存
--   direct_mail_recipients     : INDEX idx_dmr_user(user_id) 既存
--   user_line_connections      : UNIQUE KEY uq_ulc_user_id(user_id) 既存
--   tournament_individual_rankings : INDEX idx_tir_user(user_id, stat_key) 既存
--   tournament_match_rosters   : INDEX idx_tmr_user(user_id) 既存
--   tournament_match_player_stats  : INDEX idx_tmps_user(user_id, stat_key) 既存
--   committee_members.user_id  : INDEX idx_committee_members_user(user_id, left_at) 既存
--   friend_content_forwards.forwarded_by : INDEX idx_fcf_forwarded_by(forwarded_by) 既存
--
-- index 追加が必要なもの:
--   bulletin_replies.author_id
--   bulletin_threads.author_id
--   circulation_documents.created_by
--   circulation_comments.user_id
--   mentions.mentioned_by_id
--   corkboard_cards.created_by
--   sns_feed_configs.configured_by
--   tournament_promotion_records.executed_by
--   tournament_templates.created_by
--   tournaments.created_by
--   line_bot_configs.configured_by
--   direct_mail_templates.created_by
--   direct_mail_image_uploads.uploaded_by
--   ticket_consumptions.consumed_by
--   ticket_consumptions.voided_by
--   ticket_books.issued_by
--   committee_members.invited_by
--   friend_content_forwards.revoked_by

-- ===== circulation ドメイン =====

-- circulation_recipients.fk_circulation_recipients_user
-- user_id は UNIQUE KEY uk_circulation_recipients_doc_user(document_id, user_id) の第2列でカバー済 → 追加不要
ALTER TABLE circulation_recipients DROP FOREIGN KEY fk_circulation_recipients_user;

-- circulation_documents.fk_circulation_documents_created_by
-- created_by に index なし → 追加
ALTER TABLE circulation_documents DROP FOREIGN KEY fk_circulation_documents_created_by;
CREATE INDEX idx_circulation_documents_created_by ON circulation_documents (created_by);

-- circulation_comments.fk_circulation_comments_user
-- user_id に index なし → 追加
ALTER TABLE circulation_comments DROP FOREIGN KEY fk_circulation_comments_user;
CREATE INDEX idx_circulation_comments_user_id ON circulation_comments (user_id);

-- ===== bulletin ドメイン =====

-- bulletin_replies.fk_bulletin_replies_author
-- author_id に index なし → 追加（NULL可のため部分インデックスは使わず通常INDEX）
ALTER TABLE bulletin_replies DROP FOREIGN KEY fk_bulletin_replies_author;
CREATE INDEX idx_bulletin_replies_author_id ON bulletin_replies (author_id);

-- bulletin_read_status.fk_bulletin_read_status_user
-- user_id は UNIQUE KEY uk_bulletin_read_status_thread_user(thread_id, user_id) の第2列でカバー済 → 追加不要
ALTER TABLE bulletin_read_status DROP FOREIGN KEY fk_bulletin_read_status_user;

-- bulletin_threads.fk_bulletin_threads_author
-- author_id に index なし → 追加（NULL可のため通常INDEX）
ALTER TABLE bulletin_threads DROP FOREIGN KEY fk_bulletin_threads_author;
CREATE INDEX idx_bulletin_threads_author_id ON bulletin_threads (author_id);

-- ===== mention ドメイン =====

-- mentions.fk_mention_user (mentioned_user_id)
-- idx_mention_user_read(mentioned_user_id, is_read, created_at DESC) でカバー済 → 追加不要
ALTER TABLE mentions DROP FOREIGN KEY fk_mention_user;

-- mentions.fk_mention_by (mentioned_by_id)
-- mentioned_by_id に単独 index なし → 追加
ALTER TABLE mentions DROP FOREIGN KEY fk_mention_by;
CREATE INDEX idx_mentions_mentioned_by_id ON mentions (mentioned_by_id);

-- ===== corkboard ドメイン =====

-- corkboards.fk_cb_owner (owner_id)
-- idx_corkboards_personal(owner_id, deleted_at) でカバー済 → 追加不要
ALTER TABLE corkboards DROP FOREIGN KEY fk_cb_owner;

-- corkboard_cards.fk_cc_created_by (created_by)
-- created_by に index なし → 追加
ALTER TABLE corkboard_cards DROP FOREIGN KEY fk_cc_created_by;
CREATE INDEX idx_corkboard_cards_created_by ON corkboard_cards (created_by);

-- ===== sns ドメイン =====

-- sns_feed_configs.fk_sfc_configured_by (configured_by)
-- configured_by に index なし → 追加
ALTER TABLE sns_feed_configs DROP FOREIGN KEY fk_sfc_configured_by;
CREATE INDEX idx_sns_feed_configs_configured_by ON sns_feed_configs (configured_by);

-- ===== tournament ドメイン =====

-- tournament_promotion_records.fk_tpr_executed_by (executed_by)
-- executed_by に index なし → 追加
ALTER TABLE tournament_promotion_records DROP FOREIGN KEY fk_tpr_executed_by;
CREATE INDEX idx_tpr_executed_by ON tournament_promotion_records (executed_by);

-- tournament_templates.fk_tt_created_by (created_by)
-- created_by に index なし → 追加
ALTER TABLE tournament_templates DROP FOREIGN KEY fk_tt_created_by;
CREATE INDEX idx_tournament_templates_created_by ON tournament_templates (created_by);

-- tournaments.fk_t_created_by (created_by)
-- created_by に index なし → 追加
ALTER TABLE tournaments DROP FOREIGN KEY fk_t_created_by;
CREATE INDEX idx_tournaments_created_by ON tournaments (created_by);

-- tournament_individual_rankings.fk_tir_user (user_id)
-- idx_tir_user(user_id, stat_key) でカバー済 → 追加不要
ALTER TABLE tournament_individual_rankings DROP FOREIGN KEY fk_tir_user;

-- tournament_match_rosters.fk_tmr_user (user_id)
-- idx_tmr_user(user_id) でカバー済 → 追加不要
ALTER TABLE tournament_match_rosters DROP FOREIGN KEY fk_tmr_user;

-- tournament_match_player_stats.fk_tmps_user (user_id)
-- idx_tmps_user(user_id, stat_key) でカバー済 → 追加不要
ALTER TABLE tournament_match_player_stats DROP FOREIGN KEY fk_tmps_user;

-- ===== line ドメイン =====

-- user_line_connections.fk_ulc_user_id (user_id)
-- UNIQUE KEY uq_ulc_user_id(user_id) でカバー済 → 追加不要
ALTER TABLE user_line_connections DROP FOREIGN KEY fk_ulc_user_id;

-- line_bot_configs.fk_lbc_configured_by (configured_by)
-- configured_by に index なし → 追加
ALTER TABLE line_bot_configs DROP FOREIGN KEY fk_lbc_configured_by;
CREATE INDEX idx_line_bot_configs_configured_by ON line_bot_configs (configured_by);

-- ===== direct_mail ドメイン =====

-- direct_mail_templates.fk_dmt_created_by (created_by)
-- created_by に index なし → 追加
ALTER TABLE direct_mail_templates DROP FOREIGN KEY fk_dmt_created_by;
CREATE INDEX idx_direct_mail_templates_created_by ON direct_mail_templates (created_by);

-- direct_mail_image_uploads.fk_dmiu_uploaded_by (uploaded_by)
-- uploaded_by に index なし → 追加
ALTER TABLE direct_mail_image_uploads DROP FOREIGN KEY fk_dmiu_uploaded_by;
CREATE INDEX idx_direct_mail_image_uploads_uploaded_by ON direct_mail_image_uploads (uploaded_by);

-- direct_mail_logs.fk_dml_sender (sender_id)
-- idx_dml_sender(sender_id) でカバー済 → 追加不要
ALTER TABLE direct_mail_logs DROP FOREIGN KEY fk_dml_sender;

-- direct_mail_recipients.fk_dmr_user (user_id)
-- idx_dmr_user(user_id) でカバー済 → 追加不要
ALTER TABLE direct_mail_recipients DROP FOREIGN KEY fk_dmr_user;

-- ===== ticket ドメイン =====

-- ticket_consumptions.fk_tc_voided_by (voided_by, NULL可)
-- voided_by に index なし → 追加
ALTER TABLE ticket_consumptions DROP FOREIGN KEY fk_tc_voided_by;
CREATE INDEX idx_ticket_consumptions_voided_by ON ticket_consumptions (voided_by);

-- ticket_consumptions.fk_tc_consumed_by (consumed_by)
-- consumed_by に index なし → 追加
ALTER TABLE ticket_consumptions DROP FOREIGN KEY fk_tc_consumed_by;
CREATE INDEX idx_ticket_consumptions_consumed_by ON ticket_consumptions (consumed_by);

-- ticket_books.fk_tb_issued_by (issued_by, NULL可)
-- issued_by に index なし → 追加
ALTER TABLE ticket_books DROP FOREIGN KEY fk_tb_issued_by;
CREATE INDEX idx_ticket_books_issued_by ON ticket_books (issued_by);

-- ===== committee ドメイン =====

-- committee_members.fk_cm_user (user_id)
-- idx_committee_members_user(user_id, left_at) でカバー済 → 追加不要
ALTER TABLE committee_members DROP FOREIGN KEY fk_cm_user;

-- committee_members.fk_cm_invited_by (invited_by, NULL可)
-- invited_by に index なし → 追加
ALTER TABLE committee_members DROP FOREIGN KEY fk_cm_invited_by;
CREATE INDEX idx_committee_members_invited_by ON committee_members (invited_by);

-- ===== social ドメイン =====

-- friend_content_forwards.fk_fcf_forwarded_by (forwarded_by)
-- idx_fcf_forwarded_by(forwarded_by) でカバー済 → 追加不要
ALTER TABLE friend_content_forwards DROP FOREIGN KEY fk_fcf_forwarded_by;

-- friend_content_forwards.fk_fcf_revoked_by (revoked_by, NULL可)
-- revoked_by に index なし → 追加
ALTER TABLE friend_content_forwards DROP FOREIGN KEY fk_fcf_revoked_by;
CREATE INDEX idx_friend_content_forwards_revoked_by ON friend_content_forwards (revoked_by);
