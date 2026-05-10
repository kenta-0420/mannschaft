-- Phase 1-A wave6: organization_id クロスドメインFK 撤廃（20件）
--
-- 1000万ユーザー耐久DB再構築 Phase 1-A 第六波（最終波）。
-- wave5（V62.005）で 11 件を処理済み。本波で残り 20 件をすべて撤廃し
-- organization_id → organizations のクロスドメインFK を完全クローズする。
--
-- CLAUDE.md §1「クロスドメインFKは作らない」原則に従い撤廃。
-- organizations は論理削除（deleted_at）で管理されており、物理削除は発生しない。
-- CASCADE 撤廃による孤児行の発生は理論上ない。
--
-- 将来の organization 単位シャーディング・マイクロサービス分割時に
-- FK 境界をまたぐ制約が障壁になるため今のうちに撤廃する。
--
-- ━━━ 対象一覧（20件）━━━
--
--   equipment ドメイン:
--     equipment_items.fk_ei_org              (equipment → organization, no action)
--
--   payment ドメイン:
--     payment_items.fk_pi_organization       (payment → organization, ON DELETE CASCADE)
--
--   service ドメイン:
--     service_record_templates.fk_srt_org    (service → organization, no action)
--
--   team ドメイン:
--     team_pages.fk_tp_organization          (team → organization, ON DELETE CASCADE)
--     team_org_memberships.fk_team_org_memberships_org (team → organization, no action)
--
--   photo ドメイン:
--     photo_albums.fk_pa_organization        (photo → organization, ON DELETE CASCADE)
--
--   tournament ドメイン:
--     tournaments.fk_t_organization          (tournament → organization, ON DELETE CASCADE)
--     tournament_templates.fk_tt_organization (tournament → organization, ON DELETE CASCADE)
--
--   committee ドメイン:
--     committees.fk_committees_org           (committee → organization, ON DELETE CASCADE)
--
--   proxy ドメイン:
--     proxy_vote_sessions.fk_pvs_organization (proxy → organization, no action)
--
--   payment ドメイン（アクセス要件）:
--     organization_access_requirements.fk_oar_organization (payment → organization, ON DELETE CASCADE)
--
--   auth ドメイン:
--     invite_tokens.fk_invite_tokens_org     (auth → organization, no action)
--
--   social ドメイン:
--     organization_blocks.fk_org_blocks_org  (social → organization, ON DELETE CASCADE)
--
--   schedule ドメイン:
--     schedule_annual_copy_logs.fk_sacl_organization (schedule → organization, ON DELETE SET NULL)
--
--   audit ドメイン:
--     audit_logs.fk_al_org                   (audit → organization, ON DELETE SET NULL)
--
--   blog ドメイン:
--     blog_post_shares.fk_blog_share_org     (blog → organization, ON DELETE CASCADE)
--     blog_tags.fk_bt_org                    (blog → organization, ON DELETE CASCADE)
--
--   profile ドメイン:
--     member_profile_fields.fk_mpf_organization (profile → organization, ON DELETE CASCADE)
--
--   organization ドメイン（カスタムフィールド・役員）:
--     organization_custom_fields.fk_org_custom_fields_org (organization → organization, ON DELETE CASCADE)
--     organization_officers.fk_org_officers_org           (organization → organization, ON DELETE CASCADE)
--
-- ━━━ index 状況 ━━━
--
-- 既存カバー済み（追加不要）:
--   equipment_items             : INDEX idx_ei_organization_id(organization_id) 既存
--   payment_items               : INDEX idx_pi_organization_id(organization_id) 既存
--   service_record_templates    : INDEX idx_srt_org_sort(organization_id, sort_order) 既存
--   team_pages                  : INDEX idx_tp_org_status(organization_id, status, sort_order) 既存
--   photo_albums                : INDEX idx_pa_org_date(organization_id, event_date DESC) 既存
--   tournaments                 : INDEX idx_t_org_status(organization_id, status, deleted_at) 既存
--   tournament_templates        : INDEX idx_tt_org(organization_id, deleted_at) 既存
--   committees                  : INDEX idx_committees_org(organization_id, status, deleted_at) 既存
--   proxy_vote_sessions         : INDEX idx_pvs_scope(scope_type, team_id, organization_id, status) 既存
--   organization_access_requirements : UNIQUE KEY uq_oar_org_item(organization_id, payment_item_id) 既存
--   organization_blocks         : UNIQUE KEY uq_org_blocks(organization_id, user_id) 既存
--   schedule_annual_copy_logs   : INDEX idx_sacl_org(organization_id, target_academic_year) 既存
--   audit_logs                  : INDEX idx_al_organization_id(organization_id) 既存
--   blog_post_shares            : INDEX idx_bps_org(organization_id, created_at DESC) 既存
--   blog_tags                   : INDEX idx_bt_org_order(organization_id, sort_order) 既存
--   member_profile_fields       : INDEX idx_mpf_org(organization_id, sort_order) 既存
--   organization_custom_fields  : INDEX idx_org_custom_fields_org(organization_id, display_order) 既存
--   organization_officers       : INDEX idx_org_officers_org(organization_id, display_order) 既存
--
-- index 追加が必要なもの:
--   team_org_memberships : organization_id に index なし（FK のみ）→ idx 追加
--   invite_tokens        : organization_id に index なし（FK のみ）→ idx 追加
--
-- ━━━ 除外 ━━━
-- organizations.fk_organizations_parent は同一ドメイン内（自己参照）のため撤廃しない

-- ===== equipment ドメイン =====
-- equipment_items.fk_ei_org
-- organization_id は INDEX idx_ei_organization_id(organization_id) でカバー済 → 追加不要
ALTER TABLE equipment_items DROP FOREIGN KEY fk_ei_org;

-- ===== payment ドメイン =====
-- payment_items.fk_pi_organization
-- organization_id は INDEX idx_pi_organization_id(organization_id) でカバー済 → 追加不要
ALTER TABLE payment_items DROP FOREIGN KEY fk_pi_organization;

-- ===== service ドメイン =====
-- service_record_templates.fk_srt_org
-- organization_id は INDEX idx_srt_org_sort(organization_id, sort_order) でカバー済 → 追加不要
ALTER TABLE service_record_templates DROP FOREIGN KEY fk_srt_org;

-- ===== team ドメイン =====
-- team_pages.fk_tp_organization
-- organization_id は INDEX idx_tp_org_status(organization_id, ...) でカバー済 → 追加不要
ALTER TABLE team_pages DROP FOREIGN KEY fk_tp_organization;

-- team_org_memberships.fk_team_org_memberships_org
-- organization_id に index がない（FK 制約のみ）→ 追加
ALTER TABLE team_org_memberships DROP FOREIGN KEY fk_team_org_memberships_org;
CREATE INDEX idx_team_org_memberships_org ON team_org_memberships (organization_id);

-- ===== photo ドメイン =====
-- photo_albums.fk_pa_organization
-- organization_id は INDEX idx_pa_org_date(organization_id, event_date DESC) でカバー済 → 追加不要
ALTER TABLE photo_albums DROP FOREIGN KEY fk_pa_organization;

-- ===== tournament ドメイン =====
-- tournaments.fk_t_organization
-- organization_id は INDEX idx_t_org_status(organization_id, status, deleted_at) でカバー済 → 追加不要
ALTER TABLE tournaments DROP FOREIGN KEY fk_t_organization;

-- tournament_templates.fk_tt_organization
-- organization_id は INDEX idx_tt_org(organization_id, deleted_at) でカバー済 → 追加不要
ALTER TABLE tournament_templates DROP FOREIGN KEY fk_tt_organization;

-- ===== committee ドメイン =====
-- committees.fk_committees_org
-- organization_id は INDEX idx_committees_org(organization_id, status, deleted_at) でカバー済 → 追加不要
ALTER TABLE committees DROP FOREIGN KEY fk_committees_org;

-- ===== proxy ドメイン =====
-- proxy_vote_sessions.fk_pvs_organization
-- organization_id は INDEX idx_pvs_scope(scope_type, team_id, organization_id, status) でカバー済 → 追加不要
ALTER TABLE proxy_vote_sessions DROP FOREIGN KEY fk_pvs_organization;

-- ===== payment ドメイン（アクセス要件）=====
-- organization_access_requirements.fk_oar_organization
-- organization_id は UNIQUE KEY uq_oar_org_item(organization_id, payment_item_id) でカバー済 → 追加不要
ALTER TABLE organization_access_requirements DROP FOREIGN KEY fk_oar_organization;

-- ===== auth ドメイン =====
-- invite_tokens.fk_invite_tokens_org
-- organization_id に index がない（FK 制約のみ）→ 追加
ALTER TABLE invite_tokens DROP FOREIGN KEY fk_invite_tokens_org;
CREATE INDEX idx_invite_tokens_org ON invite_tokens (organization_id);

-- ===== social ドメイン =====
-- organization_blocks.fk_org_blocks_org
-- organization_id は UNIQUE KEY uq_org_blocks(organization_id, user_id) でカバー済 → 追加不要
ALTER TABLE organization_blocks DROP FOREIGN KEY fk_org_blocks_org;

-- ===== schedule ドメイン =====
-- schedule_annual_copy_logs.fk_sacl_organization
-- organization_id は INDEX idx_sacl_org(organization_id, target_academic_year) でカバー済 → 追加不要
ALTER TABLE schedule_annual_copy_logs DROP FOREIGN KEY fk_sacl_organization;

-- ===== audit ドメイン =====
-- audit_logs.fk_al_org
-- organization_id は INDEX idx_al_organization_id(organization_id) でカバー済 → 追加不要
ALTER TABLE audit_logs DROP FOREIGN KEY fk_al_org;

-- ===== blog ドメイン =====
-- blog_post_shares.fk_blog_share_org
-- organization_id は INDEX idx_bps_org(organization_id, created_at DESC) でカバー済 → 追加不要
ALTER TABLE blog_post_shares DROP FOREIGN KEY fk_blog_share_org;

-- blog_tags.fk_bt_org
-- organization_id は INDEX idx_bt_org_order(organization_id, sort_order) でカバー済 → 追加不要
ALTER TABLE blog_tags DROP FOREIGN KEY fk_bt_org;

-- ===== profile ドメイン =====
-- member_profile_fields.fk_mpf_organization
-- organization_id は INDEX idx_mpf_org(organization_id, sort_order) でカバー済 → 追加不要
ALTER TABLE member_profile_fields DROP FOREIGN KEY fk_mpf_organization;

-- ===== organization ドメイン（カスタムフィールド・役員）=====
-- organization_custom_fields.fk_org_custom_fields_org
-- organization_id は INDEX idx_org_custom_fields_org(organization_id, display_order) でカバー済 → 追加不要
ALTER TABLE organization_custom_fields DROP FOREIGN KEY fk_org_custom_fields_org;

-- organization_officers.fk_org_officers_org
-- organization_id は INDEX idx_org_officers_org(organization_id, display_order) でカバー済 → 追加不要
ALTER TABLE organization_officers DROP FOREIGN KEY fk_org_officers_org;
