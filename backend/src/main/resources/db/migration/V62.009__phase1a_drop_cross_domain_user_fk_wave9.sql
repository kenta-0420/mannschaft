-- Phase 1-A wave9: user_id クロスドメインFK撤廃（role/team/social系 30件）
--
-- 1000万ユーザー耐久DB再構築 Phase 1-A 第九波。
-- wave1〜5（V62.001〜005）の user_id / team_id / organization_id 残件に続き、
-- role ドメイン・team ドメイン・social ドメイン・moderation ドメイン・shift ドメイン等から
-- user ドメイン (users テーブル) への越境 FOREIGN KEY 30件を撤廃する。
--
-- CLAUDE.md §1「クロスドメインFKは作らない」原則に従い撤廃。
-- users は論理削除（deleted_at）・退会時匿名化（UserEntity.anonymize()）で管理されており、
-- 物理削除は発生しない。CASCADE 撤廃による孤児行の発生は理論上ない。
--
-- 対象テーブル一覧（30件）:
--
--   role ドメイン:
--     user_roles.fk_user_roles_user             (ON DELETE なし)
--     user_roles.fk_user_roles_granted_by        (ON DELETE なし)
--     user_permission_groups.fk_upg_user         (ON DELETE なし)
--     user_permission_groups.fk_upg_assigned_by  (ON DELETE なし)
--     permission_groups.fk_permission_groups_created_by (ON DELETE なし)
--
--   team ドメイン:
--     team_org_memberships.fk_team_org_memberships_invited_by   (ON DELETE なし)
--     team_org_memberships.fk_team_org_memberships_responded_by (ON DELETE なし)
--     invite_tokens.fk_invite_tokens_created_by                 (ON DELETE なし)
--     presence_events.fk_pe_user                                (ON DELETE なし)
--     team_presence_icons.fk_tpi_user                           (ON DELETE なし)
--     organization_blocks.fk_org_blocks_user                    (ON DELETE CASCADE)
--     organization_blocks.fk_org_blocks_blocked_by              (ON DELETE なし)
--     team_blocks.fk_team_blocks_user                           (ON DELETE CASCADE)
--     team_blocks.fk_team_blocks_blocked_by                     (ON DELETE なし)
--     team_anniversaries.fk_ta_user                             (ON DELETE なし)
--     duty_rotations.fk_dr_user                                 (ON DELETE なし)
--     coin_toss_results.fk_ctr_user                             (ON DELETE なし)
--     team_role_aliases.fk_tra_user                             (ON DELETE なし)
--
--   care ドメイン:
--     user_care_links.fk_ucl_recipient   (care_recipient_user_id, ON DELETE CASCADE)
--     user_care_links.fk_ucl_watcher     (watcher_user_id, ON DELETE CASCADE)
--     user_care_links.fk_ucl_created_by  (created_by, ON DELETE RESTRICT)
--     user_care_links.fk_ucl_revoked_by  (revoked_by, ON DELETE SET NULL)
--
--   social ドメイン:
--     user_social_profiles.fk_social_profiles_user (ON DELETE CASCADE)
--
--   moderation ドメイン:
--     report_actions.fk_report_actions_user          (action_by, ON DELETE RESTRICT)
--     moderation_appeals.fk_ma_user                  (ON DELETE なし)
--     report_internal_notes.fk_rin_author            (author_id, ON DELETE なし)
--     moderation_settings_history.fk_msh_user        (changed_by, ON DELETE なし)
--
--   shift ドメイン:
--     member_work_constraints.fk_mwc_user            (ON DELETE CASCADE)
--     member_availability_defaults.fk_mad_user       (ON DELETE CASCADE)
--     shift_hourly_rates.fk_shr_user                 (ON DELETE CASCADE)
--
-- ━━━ index 状況 ━━━
--
-- 既存カバー済み（追加不要）:
--   user_roles.user_id                    : UNIQUE KEY uq_user_roles_user_scope(user_id, scope_key) の先頭 → カバー済み
--   user_care_links.care_recipient_user_id: INDEX idx_ucl_recipient_status(care_recipient_user_id, status) → カバー済み
--   user_care_links.watcher_user_id       : INDEX idx_ucl_watcher_status(watcher_user_id, status) → カバー済み
--   moderation_appeals.user_id            : UNIQUE KEY uq_ma_user_action(user_id, action_id) の先頭 → カバー済み
--   member_availability_defaults.user_id  : UNIQUE KEY uq_mad_user_team_dow_time(user_id, ...) の先頭 → カバー済み
--   shift_hourly_rates.user_id            : UNIQUE KEY uq_shr_user_team_from(user_id, ...) の先頭 → カバー済み
--
-- index 追加が必要なもの:
--   user_roles.granted_by                 : FK のみ、インデックスなし → idx 追加
--   team_org_memberships.invited_by       : インデックスなし → idx 追加
--   team_org_memberships.responded_by     : インデックスなし → idx 追加
--   invite_tokens.created_by              : インデックスなし → idx 追加
--   presence_events.user_id              : idx_pe_team_user(team_id, user_id, ...) の第2列 → user_id 単独不可 → idx 追加
--   team_presence_icons.updated_by        : インデックスなし → idx 追加
--   user_permission_groups.user_id        : インデックスなし → idx 追加
--   user_permission_groups.assigned_by    : インデックスなし → idx 追加
--   permission_groups.created_by          : インデックスなし → idx 追加
--   organization_blocks.user_id           : UNIQUE uq_org_blocks(organization_id, user_id) の第2列 → user_id 単独不可 → idx 追加
--   organization_blocks.blocked_by        : インデックスなし → idx 追加
--   team_blocks.user_id                   : UNIQUE uq_team_blocks(team_id, user_id) の第2列 → user_id 単独不可 → idx 追加
--   team_blocks.blocked_by                : インデックスなし → idx 追加
--   team_anniversaries.created_by         : インデックスなし → idx 追加
--   duty_rotations.created_by             : インデックスなし → idx 追加
--   coin_toss_results.user_id             : idx_ctr_team(team_id, ...) のみ → user_id 単独不可 → idx 追加
--   team_role_aliases.updated_by          : インデックスなし → idx 追加
--   user_care_links.created_by            : インデックスなし → idx 追加
--   user_care_links.revoked_by            : インデックスなし → idx 追加
--   user_social_profiles.user_id          : UNIQUE uk_social_profiles_handle(handle) のみ → user_id インデックスなし → idx 追加
--   report_actions.action_by              : インデックスなし → idx 追加
--   report_internal_notes.author_id       : インデックスなし → idx 追加
--   moderation_settings_history.changed_by: インデックスなし → idx 追加
--   member_work_constraints.user_id       : UNIQUE uq_member_work_constraints_team_user(team_id, user_id) の第2列 → user_id 単独不可 → idx 追加

-- ===== role ドメイン =====

-- user_roles.fk_user_roles_user
-- UNIQUE KEY uq_user_roles_user_scope(user_id, scope_key) の先頭カラム → カバー済み → index 追加不要
ALTER TABLE user_roles DROP FOREIGN KEY fk_user_roles_user;

-- user_roles.fk_user_roles_granted_by
-- granted_by に index なし → 追加
ALTER TABLE user_roles DROP FOREIGN KEY fk_user_roles_granted_by;
CREATE INDEX idx_user_roles_granted_by ON user_roles (granted_by);

-- user_permission_groups.fk_upg_user
-- user_id に index なし → 追加
ALTER TABLE user_permission_groups DROP FOREIGN KEY fk_upg_user;
CREATE INDEX idx_upg_user_id ON user_permission_groups (user_id);

-- user_permission_groups.fk_upg_assigned_by
-- assigned_by に index なし → 追加
ALTER TABLE user_permission_groups DROP FOREIGN KEY fk_upg_assigned_by;
CREATE INDEX idx_upg_assigned_by ON user_permission_groups (assigned_by);

-- permission_groups.fk_permission_groups_created_by
-- created_by に index なし → 追加
ALTER TABLE permission_groups DROP FOREIGN KEY fk_permission_groups_created_by;
CREATE INDEX idx_permission_groups_created_by ON permission_groups (created_by);

-- ===== team ドメイン =====

-- team_org_memberships.fk_team_org_memberships_invited_by
-- invited_by に index なし → 追加
ALTER TABLE team_org_memberships DROP FOREIGN KEY fk_team_org_memberships_invited_by;
CREATE INDEX idx_team_org_memberships_invited_by ON team_org_memberships (invited_by);

-- team_org_memberships.fk_team_org_memberships_responded_by
-- responded_by に index なし → 追加
ALTER TABLE team_org_memberships DROP FOREIGN KEY fk_team_org_memberships_responded_by;
CREATE INDEX idx_team_org_memberships_responded_by ON team_org_memberships (responded_by);

-- invite_tokens.fk_invite_tokens_created_by
-- created_by に index なし → 追加
ALTER TABLE invite_tokens DROP FOREIGN KEY fk_invite_tokens_created_by;
CREATE INDEX idx_invite_tokens_created_by ON invite_tokens (created_by);

-- presence_events.fk_pe_user
-- INDEX idx_pe_team_user(team_id, user_id, ...) の第2列 → user_id 単独検索不可 → idx 追加
ALTER TABLE presence_events DROP FOREIGN KEY fk_pe_user;
CREATE INDEX idx_presence_events_user_id ON presence_events (user_id);

-- team_presence_icons.fk_tpi_user
-- updated_by に index なし → 追加
ALTER TABLE team_presence_icons DROP FOREIGN KEY fk_tpi_user;
CREATE INDEX idx_tpi_updated_by ON team_presence_icons (updated_by);

-- organization_blocks.fk_org_blocks_user
-- UNIQUE KEY uq_org_blocks(organization_id, user_id) の第2列 → user_id 単独検索不可 → idx 追加
ALTER TABLE organization_blocks DROP FOREIGN KEY fk_org_blocks_user;
CREATE INDEX idx_org_blocks_user_id ON organization_blocks (user_id);

-- organization_blocks.fk_org_blocks_blocked_by
-- blocked_by に index なし → 追加
ALTER TABLE organization_blocks DROP FOREIGN KEY fk_org_blocks_blocked_by;
CREATE INDEX idx_org_blocks_blocked_by ON organization_blocks (blocked_by);

-- team_blocks.fk_team_blocks_user
-- UNIQUE KEY uq_team_blocks(team_id, user_id) の第2列 → user_id 単独検索不可 → idx 追加
ALTER TABLE team_blocks DROP FOREIGN KEY fk_team_blocks_user;
CREATE INDEX idx_team_blocks_user_id ON team_blocks (user_id);

-- team_blocks.fk_team_blocks_blocked_by
-- blocked_by に index なし → 追加
ALTER TABLE team_blocks DROP FOREIGN KEY fk_team_blocks_blocked_by;
CREATE INDEX idx_team_blocks_blocked_by ON team_blocks (blocked_by);

-- team_anniversaries.fk_ta_user
-- created_by に index なし → 追加
ALTER TABLE team_anniversaries DROP FOREIGN KEY fk_ta_user;
CREATE INDEX idx_ta_created_by ON team_anniversaries (created_by);

-- duty_rotations.fk_dr_user
-- created_by に index なし → 追加
ALTER TABLE duty_rotations DROP FOREIGN KEY fk_dr_user;
CREATE INDEX idx_dr_created_by ON duty_rotations (created_by);

-- coin_toss_results.fk_ctr_user
-- INDEX idx_ctr_team(team_id, ...) のみ → user_id 単独検索不可 → idx 追加
ALTER TABLE coin_toss_results DROP FOREIGN KEY fk_ctr_user;
CREATE INDEX idx_ctr_user_id ON coin_toss_results (user_id);

-- team_role_aliases.fk_tra_user
-- updated_by に index なし → 追加
ALTER TABLE team_role_aliases DROP FOREIGN KEY fk_tra_user;
CREATE INDEX idx_tra_updated_by ON team_role_aliases (updated_by);

-- ===== care ドメイン =====

-- user_care_links.fk_ucl_recipient
-- INDEX idx_ucl_recipient_status(care_recipient_user_id, status) でカバー済み → 追加不要
ALTER TABLE user_care_links DROP FOREIGN KEY fk_ucl_recipient;

-- user_care_links.fk_ucl_watcher
-- INDEX idx_ucl_watcher_status(watcher_user_id, status) でカバー済み → 追加不要
ALTER TABLE user_care_links DROP FOREIGN KEY fk_ucl_watcher;

-- user_care_links.fk_ucl_created_by
-- created_by に index なし → 追加
ALTER TABLE user_care_links DROP FOREIGN KEY fk_ucl_created_by;
CREATE INDEX idx_ucl_created_by ON user_care_links (created_by);

-- user_care_links.fk_ucl_revoked_by
-- revoked_by に index なし → 追加
ALTER TABLE user_care_links DROP FOREIGN KEY fk_ucl_revoked_by;
CREATE INDEX idx_ucl_revoked_by ON user_care_links (revoked_by);

-- ===== social ドメイン =====

-- user_social_profiles.fk_social_profiles_user
-- UNIQUE KEY uk_social_profiles_handle(handle) のみ → user_id に index なし → 追加
ALTER TABLE user_social_profiles DROP FOREIGN KEY fk_social_profiles_user;
CREATE INDEX idx_social_profiles_user_id ON user_social_profiles (user_id);

-- ===== moderation ドメイン =====

-- report_actions.fk_report_actions_user
-- action_by に index なし → 追加
ALTER TABLE report_actions DROP FOREIGN KEY fk_report_actions_user;
CREATE INDEX idx_report_actions_action_by ON report_actions (action_by);

-- moderation_appeals.fk_ma_user
-- UNIQUE KEY uq_ma_user_action(user_id, action_id) の先頭カラム → カバー済み → 追加不要
ALTER TABLE moderation_appeals DROP FOREIGN KEY fk_ma_user;

-- report_internal_notes.fk_rin_author
-- author_id に index なし → 追加
ALTER TABLE report_internal_notes DROP FOREIGN KEY fk_rin_author;
CREATE INDEX idx_rin_author_id ON report_internal_notes (author_id);

-- moderation_settings_history.fk_msh_user
-- changed_by に index なし → 追加
ALTER TABLE moderation_settings_history DROP FOREIGN KEY fk_msh_user;
CREATE INDEX idx_msh_changed_by ON moderation_settings_history (changed_by);

-- ===== shift ドメイン =====

-- member_work_constraints.fk_mwc_user
-- UNIQUE KEY uq_member_work_constraints_team_user(team_id, user_id) の第2列 → user_id 単独検索不可 → idx 追加
ALTER TABLE member_work_constraints DROP FOREIGN KEY fk_mwc_user;
CREATE INDEX idx_mwc_user_id ON member_work_constraints (user_id);

-- member_availability_defaults.fk_mad_user
-- UNIQUE KEY uq_mad_user_team_dow_time(user_id, ...) の先頭カラム → カバー済み → 追加不要
ALTER TABLE member_availability_defaults DROP FOREIGN KEY fk_mad_user;

-- shift_hourly_rates.fk_shr_user
-- UNIQUE KEY uq_shr_user_team_from(user_id, ...) の先頭カラム → カバー済み → 追加不要
ALTER TABLE shift_hourly_rates DROP FOREIGN KEY fk_shr_user;
