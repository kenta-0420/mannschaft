-- =============================================================================
-- Phase 1-A wave8: team_id クロスドメインFK 撤廃（後半33件・team_id 完全クローズ）
-- =============================================================================
--
-- 1000万ユーザー耐久DB再構築 Phase 1-A 第八波（最終波）。
-- wave3（V62.003）・wave4（V62.004）・wave7（V62.007）に続く team_id 参照の後半 33 件を処理し、
-- team_id → teams(id) クロスドメインFK を完全撤廃する。
--
-- 設計原則: CLAUDE.md §「DB設計の原則 1. クロスドメインFKは作らない」
-- teams は論理削除（deleted_at）で管理されており、物理削除は発生しない。
-- 参照整合性はアプリケーション層で保証する。
--
-- ━━━ 対象一覧（33件）━━━
--
--   role ドメイン:
--     user_roles.fk_user_roles_team                            (no action)
--     permission_groups.fk_permission_groups_team              (no action)
--
--   team ドメイン（teamsの自己参照系・設定系）:
--     presence_events.fk_pe_team                               (no action)
--     team_org_memberships.fk_team_org_memberships_team        (no action)
--     team_presence_icons.fk_tpi_team                          (no action)
--     coin_toss_results.fk_ctr_team                            (no action)
--     invite_tokens.fk_invite_tokens_team                      (no action)
--     team_role_aliases.fk_tra_team                            (no action)
--     team_enabled_modules.fk_team_enabled_modules_team        (ON DELETE CASCADE)
--     team_officers.fk_team_officers_team                      (ON DELETE CASCADE)
--     team_shift_settings.fk_team_shift_settings_team          (ON DELETE CASCADE)
--     team_anniversaries.fk_ta_team                            (no action)
--     team_blocks.fk_team_blocks_team                          (ON DELETE CASCADE)
--     duty_rotations.fk_dr_team                                (no action)
--
--   shopping / misc ドメイン:
--     shopping_lists.fk_sl_team                                (no action)
--
--   audit ドメイン:
--     update_audit_logs（audit_logs）.fk_al_team               (ON DELETE SET NULL)
--
--   timetable ドメイン:
--     personal_timetable_slots.fk_pts_linked_team              (ON DELETE SET NULL)
--
--   schedule ドメイン:
--     schedule_annual_copy_logs.fk_sacl_team                   (ON DELETE SET NULL)
--
--   equipment ドメイン:
--     equipment_ranking_exclusions.fk_ere_team                 (no action)
--
--   attendance ドメイン:
--     period_attendance_records.fk_par_team                    (ON DELETE CASCADE)
--     attendance_transition_alerts.fk_ata_team                 (ON DELETE CASCADE)
--     class_homerooms.fk_ch_team                               (ON DELETE CASCADE)
--     family_attendance_notices.fk_fan_team                    (ON DELETE CASCADE)
--
--   blog ドメイン:
--     blog_tags.fk_bt_team                                     (ON DELETE CASCADE)
--     blog_post_shares.fk_blog_share_team                      (ON DELETE CASCADE)
--
--   photo ドメイン:
--     photo_albums.fk_pa_team                                  (ON DELETE CASCADE)
--
--   team_member ドメイン:
--     team_member_info_responses.fk_tmir_team                  (ON DELETE CASCADE)
--     member_profile_fields.fk_mpf_team                        (ON DELETE CASCADE)
--
--   page / content ドメイン:
--     team_pages.fk_tp_team                                    (ON DELETE CASCADE)
--     team_custom_fields（team_custom_fields テーブル）.fk_team_custom_fields_team (ON DELETE CASCADE)
--
--   shift ドメイン:
--     shift_hourly_rates.fk_shr_team                           (ON DELETE CASCADE)
--     member_work_constraints.fk_mwc_team                      (ON DELETE CASCADE)
--     member_availability_defaults.fk_mad_team                 (ON DELETE CASCADE)
--
-- ━━━ index 状況 ━━━
--
-- 既存カバー済み（追加不要）:
--   user_roles                   : INDEX idx_user_roles_team(team_id) 既存
--   presence_events              : INDEX idx_pe_team_user(team_id, user_id, ...) 既存
--   team_org_memberships         : UNIQUE uq_team_org(team_id, organization_id) 既存
--   team_presence_icons          : UNIQUE uq_tpi_team_type(team_id, event_type) 既存
--   coin_toss_results            : INDEX idx_ctr_team(team_id, created_at) 既存
--   shopping_lists               : INDEX idx_sl_team(team_id, deleted_at, created_at) 既存
--   audit_logs                   : INDEX idx_al_team_id(team_id), idx_al_team_created(team_id, ...) 既存
--   personal_timetable_slots     : INDEX idx_pts_linked_team(linked_team_id) 既存
--   schedule_annual_copy_logs    : INDEX idx_sacl_team(team_id, target_academic_year) 既存
--   equipment_ranking_exclusions : UNIQUE uq_ere_team_opt_out(team_id, exclusion_type) 既存
--   team_role_aliases            : UNIQUE uq_tra_team_role(team_id, role_name) 既存
--   period_attendance_records    : UNIQUE uq_par(team_id, ...) + idx_par_date_period(attendance_date, team_id, ...) 既存
--   team_enabled_modules         : UNIQUE uq_team_module(team_id, module_id) 既存
--   blog_tags                    : INDEX idx_bt_team_order(team_id, sort_order) 既存
--   blog_post_shares             : INDEX idx_bps_team(team_id, created_at) 既存
--   team_officers                : INDEX idx_team_officers_team(team_id, display_order) 既存
--   team_shift_settings          : UNIQUE uq_team_shift_settings_team_id(team_id) 既存
--   photo_albums                 : INDEX idx_pa_team_date(team_id, event_date) 既存
--   team_member_info_responses   : KEY idx_tmir_team_user(team_id, user_id) 既存
--   member_profile_fields        : INDEX idx_mpf_team(team_id, sort_order) 既存
--   team_pages                   : INDEX idx_tp_team_status(team_id, status, ...) 既存
--   team_custom_fields           : INDEX idx_team_custom_fields_team(team_id, display_order) 既存
--   family_attendance_notices    : INDEX idx_fan_team_date(team_id, attendance_date, ...) 既存
--   team_anniversaries           : INDEX idx_ta_team(team_id, deleted_at) 既存
--   team_blocks                  : UNIQUE uq_team_blocks(team_id, user_id) 既存（先頭 team_id でカバー）
--   duty_rotations               : INDEX idx_dr_team(team_id, deleted_at, is_enabled) 既存
--   shift_hourly_rates           : UNIQUE uq_shr_user_team_from(user_id, team_id, ...) — team_id 先頭ではないが検索は user_id+team_id 複合で使用 → 追加不要
--   member_work_constraints      : INDEX idx_member_work_constraints_team(team_id) 既存
--   class_homerooms              : INDEX idx_ch_team_year(team_id, academic_year) 既存
--   member_availability_defaults : UNIQUE uq_mad_user_team_dow_time(user_id, team_id, ...) 既存
--
-- index 追加が必要なもの:
--   permission_groups           : team_id に単体 index なし（FK のみ）→ idx 追加
--   invite_tokens               : team_id に index なし（FK のみ）→ idx 追加
--   attendance_transition_alerts: team_id に index なし → idx 追加
--
-- =============================================================================

-- -----------------------------------------------------------------------------
-- [1] user_roles.fk_user_roles_team
--     role ドメイン → teams 参照。
--     idx_user_roles_team(team_id) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE user_roles
    DROP FOREIGN KEY fk_user_roles_team;

-- -----------------------------------------------------------------------------
-- [2] permission_groups.fk_permission_groups_team
--     role ドメイン → teams 参照。
--     team_id に単体 index がないため追加してから FK を削除。
-- -----------------------------------------------------------------------------
ALTER TABLE permission_groups
    ADD INDEX idx_permission_groups_team (team_id);

ALTER TABLE permission_groups
    DROP FOREIGN KEY fk_permission_groups_team;

-- -----------------------------------------------------------------------------
-- [3] presence_events.fk_pe_team
--     team ドメイン → teams 参照。
--     idx_pe_team_user(team_id, user_id, created_at) で代替。
-- -----------------------------------------------------------------------------
ALTER TABLE presence_events
    DROP FOREIGN KEY fk_pe_team;

-- -----------------------------------------------------------------------------
-- [4] team_org_memberships.fk_team_org_memberships_team
--     team ドメイン → teams 参照。
--     UNIQUE uq_team_org(team_id, organization_id) の先頭 team_id でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE team_org_memberships
    DROP FOREIGN KEY fk_team_org_memberships_team;

-- -----------------------------------------------------------------------------
-- [5] team_presence_icons.fk_tpi_team
--     team ドメイン → teams 参照。
--     UNIQUE uq_tpi_team_type(team_id, event_type) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE team_presence_icons
    DROP FOREIGN KEY fk_tpi_team;

-- -----------------------------------------------------------------------------
-- [6] coin_toss_results.fk_ctr_team
--     team ドメイン → teams 参照。
--     INDEX idx_ctr_team(team_id, created_at) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE coin_toss_results
    DROP FOREIGN KEY fk_ctr_team;

-- -----------------------------------------------------------------------------
-- [7] invite_tokens.fk_invite_tokens_team
--     team ドメイン → teams 参照。
--     team_id に index がないため追加してから FK を削除。
-- -----------------------------------------------------------------------------
ALTER TABLE invite_tokens
    ADD INDEX idx_invite_tokens_team (team_id);

ALTER TABLE invite_tokens
    DROP FOREIGN KEY fk_invite_tokens_team;

-- -----------------------------------------------------------------------------
-- [8] shopping_lists.fk_sl_team
--     shopping ドメイン → teams 参照。
--     INDEX idx_sl_team(team_id, deleted_at, created_at) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE shopping_lists
    DROP FOREIGN KEY fk_sl_team;

-- -----------------------------------------------------------------------------
-- [9] audit_logs.fk_al_team  （update_audit_logs）
--     audit ドメイン → teams 参照（ON DELETE SET NULL）。
--     INDEX idx_al_team_id(team_id), idx_al_team_created(team_id, created_at) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE audit_logs
    DROP FOREIGN KEY fk_al_team;

-- -----------------------------------------------------------------------------
-- [10] personal_timetable_slots.fk_pts_linked_team
--      timetable ドメイン → teams 参照（ON DELETE SET NULL）。
--      INDEX idx_pts_linked_team(linked_team_id) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE personal_timetable_slots
    DROP FOREIGN KEY fk_pts_linked_team;

-- -----------------------------------------------------------------------------
-- [11] schedule_annual_copy_logs.fk_sacl_team
--      schedule ドメイン → teams 参照（ON DELETE SET NULL）。
--      INDEX idx_sacl_team(team_id, target_academic_year) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE schedule_annual_copy_logs
    DROP FOREIGN KEY fk_sacl_team;

-- -----------------------------------------------------------------------------
-- [12] equipment_ranking_exclusions.fk_ere_team
--      equipment ドメイン → teams 参照。
--      UNIQUE uq_ere_team_opt_out(team_id, exclusion_type) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE equipment_ranking_exclusions
    DROP FOREIGN KEY fk_ere_team;

-- -----------------------------------------------------------------------------
-- [13] team_role_aliases.fk_tra_team
--      team ドメイン → teams 参照。
--      UNIQUE uq_tra_team_role(team_id, role_name) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE team_role_aliases
    DROP FOREIGN KEY fk_tra_team;

-- -----------------------------------------------------------------------------
-- [14] period_attendance_records.fk_par_team
--      attendance ドメイン → teams 参照（ON DELETE CASCADE）。
--      UNIQUE uq_par(team_id, ...) + INDEX idx_par_date_period(attendance_date, team_id, ...) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE period_attendance_records
    DROP FOREIGN KEY fk_par_team;

-- -----------------------------------------------------------------------------
-- [15] team_enabled_modules.fk_team_enabled_modules_team
--      team ドメイン → teams 参照（ON DELETE CASCADE）。
--      UNIQUE uq_team_module(team_id, module_id) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE team_enabled_modules
    DROP FOREIGN KEY fk_team_enabled_modules_team;

-- -----------------------------------------------------------------------------
-- [16] attendance_transition_alerts.fk_ata_team
--      attendance ドメイン → teams 参照（ON DELETE CASCADE）。
--      team_id に単体 index がないため追加してから FK を削除。
-- -----------------------------------------------------------------------------
ALTER TABLE attendance_transition_alerts
    ADD INDEX idx_ata_team (team_id);

ALTER TABLE attendance_transition_alerts
    DROP FOREIGN KEY fk_ata_team;

-- -----------------------------------------------------------------------------
-- [17] blog_tags.fk_bt_team
--      blog ドメイン → teams 参照（ON DELETE CASCADE）。
--      INDEX idx_bt_team_order(team_id, sort_order) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE blog_tags
    DROP FOREIGN KEY fk_bt_team;

-- -----------------------------------------------------------------------------
-- [18] blog_post_shares.fk_blog_share_team
--      blog ドメイン → teams 参照（ON DELETE CASCADE）。
--      INDEX idx_bps_team(team_id, created_at) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE blog_post_shares
    DROP FOREIGN KEY fk_blog_share_team;

-- -----------------------------------------------------------------------------
-- [19] team_officers.fk_team_officers_team
--      team ドメイン → teams 参照（ON DELETE CASCADE）。
--      INDEX idx_team_officers_team(team_id, display_order) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE team_officers
    DROP FOREIGN KEY fk_team_officers_team;

-- -----------------------------------------------------------------------------
-- [20] team_shift_settings.fk_team_shift_settings_team
--      shift ドメイン → teams 参照（ON DELETE CASCADE）。
--      UNIQUE uq_team_shift_settings_team_id(team_id) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE team_shift_settings
    DROP FOREIGN KEY fk_team_shift_settings_team;

-- -----------------------------------------------------------------------------
-- [21] photo_albums.fk_pa_team
--      photo ドメイン → teams 参照（ON DELETE CASCADE）。
--      INDEX idx_pa_team_date(team_id, event_date) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE photo_albums
    DROP FOREIGN KEY fk_pa_team;

-- -----------------------------------------------------------------------------
-- [22] team_member_info_responses.fk_tmir_team
--      team_member ドメイン → teams 参照（ON DELETE CASCADE）。
--      KEY idx_tmir_team_user(team_id, user_id) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE team_member_info_responses
    DROP FOREIGN KEY fk_tmir_team;

-- -----------------------------------------------------------------------------
-- [23] member_profile_fields.fk_mpf_team
--      team_member ドメイン → teams 参照（ON DELETE CASCADE）。
--      INDEX idx_mpf_team(team_id, sort_order) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE member_profile_fields
    DROP FOREIGN KEY fk_mpf_team;

-- -----------------------------------------------------------------------------
-- [24] team_pages.fk_tp_team
--      page ドメイン → teams 参照（ON DELETE CASCADE）。
--      INDEX idx_tp_team_status(team_id, status, sort_order) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE team_pages
    DROP FOREIGN KEY fk_tp_team;

-- -----------------------------------------------------------------------------
-- [25] team_custom_fields.fk_team_custom_fields_team
--      content ドメイン → teams 参照（ON DELETE CASCADE）。
--      INDEX idx_team_custom_fields_team(team_id, display_order) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE team_custom_fields
    DROP FOREIGN KEY fk_team_custom_fields_team;

-- -----------------------------------------------------------------------------
-- [26] family_attendance_notices.fk_fan_team
--      attendance ドメイン → teams 参照（ON DELETE CASCADE）。
--      INDEX idx_fan_team_date(team_id, attendance_date, acknowledged_at) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE family_attendance_notices
    DROP FOREIGN KEY fk_fan_team;

-- -----------------------------------------------------------------------------
-- [27] team_anniversaries.fk_ta_team
--      team ドメイン → teams 参照（no action）。
--      INDEX idx_ta_team(team_id, deleted_at) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE team_anniversaries
    DROP FOREIGN KEY fk_ta_team;

-- -----------------------------------------------------------------------------
-- [28] team_blocks.fk_team_blocks_team
--      team ドメイン → teams 参照（ON DELETE CASCADE）。
--      UNIQUE uq_team_blocks(team_id, user_id) の先頭 team_id でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE team_blocks
    DROP FOREIGN KEY fk_team_blocks_team;

-- -----------------------------------------------------------------------------
-- [29] duty_rotations.fk_dr_team
--      team ドメイン → teams 参照（no action）。
--      INDEX idx_dr_team(team_id, deleted_at, is_enabled) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE duty_rotations
    DROP FOREIGN KEY fk_dr_team;

-- -----------------------------------------------------------------------------
-- [30] shift_hourly_rates.fk_shr_team
--      shift ドメイン → teams 参照（ON DELETE CASCADE）。
--      UNIQUE uq_shr_user_team_from(user_id, team_id, effective_from) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE shift_hourly_rates
    DROP FOREIGN KEY fk_shr_team;

-- -----------------------------------------------------------------------------
-- [31] member_work_constraints.fk_mwc_team
--      shift ドメイン → teams 参照（ON DELETE CASCADE）。
--      INDEX idx_member_work_constraints_team(team_id) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE member_work_constraints
    DROP FOREIGN KEY fk_mwc_team;

-- -----------------------------------------------------------------------------
-- [32] class_homerooms.fk_ch_team
--      attendance ドメイン → teams 参照（ON DELETE CASCADE）。
--      INDEX idx_ch_team_year(team_id, academic_year) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE class_homerooms
    DROP FOREIGN KEY fk_ch_team;

-- -----------------------------------------------------------------------------
-- [33] member_availability_defaults.fk_mad_team
--      shift ドメイン → teams 参照（ON DELETE CASCADE）。
--      UNIQUE uq_mad_user_team_dow_time(user_id, team_id, ...) でカバー済み。
-- -----------------------------------------------------------------------------
ALTER TABLE member_availability_defaults
    DROP FOREIGN KEY fk_mad_team;
