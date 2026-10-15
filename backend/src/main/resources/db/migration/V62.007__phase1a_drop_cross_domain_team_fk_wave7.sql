-- =============================================================================
-- Phase 1-A wave7: team_id クロスドメインFK 撤廃（前半35件）
-- 対象: chart/service/match/equipment/tournament/ticket/team_friends/ng_teams
--       payment/proxy_vote_sessions/daily_attendance_records 各ドメイン
-- 設計原則: CLAUDE.md §「DB設計の原則 1. クロスドメインFKは作らない」
-- 参照整合性はアプリケーション層で保証する。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- [1] chart_record_templates.fk_crt_team
--     teams(id) 参照。idx_crt_team_sort(team_id, sort_order) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE chart_record_templates
    DROP FOREIGN KEY fk_crt_team;

-- -----------------------------------------------------------------------------
-- [2] match_requests.fk_mr_team
--     teams(id) ON DELETE CASCADE 参照。idx_mr_team(team_id, status, created_at) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE match_requests
    DROP FOREIGN KEY fk_mr_team;

-- -----------------------------------------------------------------------------
-- [3] chart_section_settings.fk_css_team
--     teams(id) 参照。uq_css_team_section(team_id, section_type) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE chart_section_settings
    DROP FOREIGN KEY fk_css_team;

-- -----------------------------------------------------------------------------
-- [4] chart_intake_form_templates.fk_cift_team
--     teams(id) 参照。idx_cift_team(team_id) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE chart_intake_form_templates
    DROP FOREIGN KEY fk_cift_team;

-- -----------------------------------------------------------------------------
-- [5] match_reviews.fk_mrev_reviewer（reviewer_team_id → teams）
--     uq_mr_proposal_reviewer(proposal_id, reviewer_team_id) は reviewer_team_id
--     単体検索に非効率なため、専用インデックスを追加してから FK を削除。
-- -----------------------------------------------------------------------------
ALTER TABLE match_reviews
    ADD INDEX idx_mrev_reviewer_team (reviewer_team_id);

ALTER TABLE match_reviews
    DROP FOREIGN KEY fk_mrev_reviewer;

-- -----------------------------------------------------------------------------
-- [6] match_reviews.fk_mrev_reviewee（reviewee_team_id → teams）
--     idx_mr_reviewee(reviewee_team_id, created_at) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE match_reviews
    DROP FOREIGN KEY fk_mrev_reviewee;

-- -----------------------------------------------------------------------------
-- [7] match_proposals.fk_mp_proposing_team（proposing_team_id → teams）
--     idx_mp_proposing_team(proposing_team_id, status) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE match_proposals
    DROP FOREIGN KEY fk_mp_proposing_team;

-- -----------------------------------------------------------------------------
-- [8] match_proposals.fk_mp_cancelled_by（cancelled_by_team_id → teams）
--     既存インデックスなし。専用インデックスを追加してから FK を削除。
-- -----------------------------------------------------------------------------
ALTER TABLE match_proposals
    ADD INDEX idx_mp_cancelled_by_team (cancelled_by_team_id);

ALTER TABLE match_proposals
    DROP FOREIGN KEY fk_mp_cancelled_by;

-- -----------------------------------------------------------------------------
-- [9] equipment_items.fk_ei_team（team_id → teams）
--     idx_ei_team_id(team_id) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE equipment_items
    DROP FOREIGN KEY fk_ei_team;

-- -----------------------------------------------------------------------------
-- [10] service_records.fk_sr_team（team_id → teams）
--      idx_sr_team_id(team_id) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE service_records
    DROP FOREIGN KEY fk_sr_team;

-- -----------------------------------------------------------------------------
-- [11] service_record_settings.fk_srs_team（team_id → teams）
--      uq_srs_team(team_id) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE service_record_settings
    DROP FOREIGN KEY fk_srs_team;

-- -----------------------------------------------------------------------------
-- [12] team_member_info_fields.fk_tmif_team（team_id → teams）
--      idx_tmif_team(team_id) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE team_member_info_fields
    DROP FOREIGN KEY fk_tmif_team;

-- -----------------------------------------------------------------------------
-- [13] service_record_fields.fk_srf_team（team_id → teams）
--      idx_srf_team_sort(team_id, sort_order) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE service_record_fields
    DROP FOREIGN KEY fk_srf_team;

-- -----------------------------------------------------------------------------
-- [14] chart_custom_fields.fk_ccf_team（team_id → teams）
--      idx_ccf_team_sort(team_id, sort_order) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE chart_custom_fields
    DROP FOREIGN KEY fk_ccf_team;

-- -----------------------------------------------------------------------------
-- [15] chart_records.fk_cr_team（team_id → teams）
--      idx_cr_team_id(team_id) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE chart_records
    DROP FOREIGN KEY fk_cr_team;

-- -----------------------------------------------------------------------------
-- [16] service_record_templates.fk_srt_team（team_id → teams）
--      idx_srt_team_sort(team_id, sort_order) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE service_record_templates
    DROP FOREIGN KEY fk_srt_team;

-- -----------------------------------------------------------------------------
-- [17] performance_metrics.fk_pm_team（team_id → teams）
--      idx_pm_team_sort(team_id, sort_order) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE performance_metrics
    DROP FOREIGN KEY fk_pm_team;

-- -----------------------------------------------------------------------------
-- [18] tournament_promotion_records.fk_tpr_team（team_id → teams）
--      idx_tpr_team(team_id) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE tournament_promotion_records
    DROP FOREIGN KEY fk_tpr_team;

-- -----------------------------------------------------------------------------
-- [19] team_friend_folders.fk_tff_team（team_id → teams）
--      idx_tff_team_active(team_id, deleted_at, sort_order) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE team_friend_folders
    DROP FOREIGN KEY fk_tff_team;

-- -----------------------------------------------------------------------------
-- [20] ticket_books.fk_tb_team（team_id → teams）
--      idx_tb_team(team_id, status, created_at DESC) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE ticket_books
    DROP FOREIGN KEY fk_tb_team;

-- -----------------------------------------------------------------------------
-- [21] tournament_participants.fk_tourn_part_team（team_id → teams）
--      idx_tp_team(team_id) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE tournament_participants
    DROP FOREIGN KEY fk_tourn_part_team;

-- -----------------------------------------------------------------------------
-- [22] friend_content_forwards.fk_fcf_source_team（source_team_id → teams）
--      idx_fcf_source_team(source_team_id, forwarded_at DESC) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE friend_content_forwards
    DROP FOREIGN KEY fk_fcf_source_team;

-- -----------------------------------------------------------------------------
-- [23] friend_content_forwards.fk_fcf_forwarding_team（forwarding_team_id → teams）
--      idx_fcf_forwarding_team(forwarding_team_id, forwarded_at DESC) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE friend_content_forwards
    DROP FOREIGN KEY fk_fcf_forwarding_team;

-- -----------------------------------------------------------------------------
-- [24] team_friends.fk_tf_team_a（team_a_id → teams）
--      idx_tf_team_a(team_a_id, established_at DESC) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE team_friends
    DROP FOREIGN KEY fk_tf_team_a;

-- -----------------------------------------------------------------------------
-- [25] team_friends.fk_tf_team_b（team_b_id → teams）
--      idx_tf_team_b(team_b_id, established_at DESC) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE team_friends
    DROP FOREIGN KEY fk_tf_team_b;

-- -----------------------------------------------------------------------------
-- [26] ticket_payments.fk_tpay_team（team_id → teams）
--      idx_tpay_team(team_id, status, created_at DESC) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE ticket_payments
    DROP FOREIGN KEY fk_tpay_team;

-- -----------------------------------------------------------------------------
-- [27] ng_teams.fk_ng_blocked（blocked_team_id → teams）
--      idx_ng_blocked(blocked_team_id) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE ng_teams
    DROP FOREIGN KEY fk_ng_blocked;

-- -----------------------------------------------------------------------------
-- [28] ng_teams.fk_ng_team（team_id → teams）
--      idx_ng_team(team_id) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE ng_teams
    DROP FOREIGN KEY fk_ng_team;

-- -----------------------------------------------------------------------------
-- [29] payment_items.fk_pi_team（team_id → teams）
--      idx_pi_team_id(team_id) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE payment_items
    DROP FOREIGN KEY fk_pi_team;

-- -----------------------------------------------------------------------------
-- [30] match_request_templates.fk_mrt_team（team_id → teams）
--      idx_mrt_team(team_id) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE match_request_templates
    DROP FOREIGN KEY fk_mrt_team;

-- -----------------------------------------------------------------------------
-- [31] team_access_requirements.fk_tar_team（team_id → teams）
--      uq_tar_team_item(team_id, payment_item_id) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE team_access_requirements
    DROP FOREIGN KEY fk_tar_team;

-- -----------------------------------------------------------------------------
-- [32] ticket_products.fk_ticket_prod_team（team_id → teams）
--      idx_tp_team(team_id, deleted_at, is_active, sort_order) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE ticket_products
    DROP FOREIGN KEY fk_ticket_prod_team;

-- -----------------------------------------------------------------------------
-- [33] match_notification_preferences.fk_mnp_team（team_id → teams）
--      uq_mnp_team(team_id) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE match_notification_preferences
    DROP FOREIGN KEY fk_mnp_team;

-- -----------------------------------------------------------------------------
-- [34] proxy_vote_sessions.fk_pvs_team（team_id → teams）
--      既存 idx_pvs_scope(scope_type, team_id, ...) はプレフィックス非一致で非効率。
--      専用インデックスを追加してから FK を削除。
-- -----------------------------------------------------------------------------
ALTER TABLE proxy_vote_sessions
    ADD INDEX idx_pvs_team_id (team_id);

ALTER TABLE proxy_vote_sessions
    DROP FOREIGN KEY fk_pvs_team;

-- -----------------------------------------------------------------------------
-- [35] daily_attendance_records.fk_dar_team（team_id → teams）
--      uq_dar(team_id, student_user_id, attendance_date) の先頭列で代替可能。
-- -----------------------------------------------------------------------------
ALTER TABLE daily_attendance_records
    DROP FOREIGN KEY fk_dar_team;
