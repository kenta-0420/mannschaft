-- Phase 1-A wave5: organization_id クロスドメインFK 撤廃（11件）
--
-- 1000万ユーザー耐久DB再構築 Phase 1-A 第五波。
-- organization_id 参照（organizations テーブルへの越境FK）の初波として 11 件を処理する。
-- wave1〜3（V62.001〜003）で user_id / team_id 参照を整理済み。
--
-- CLAUDE.md §1「クロスドメインFKは作らない」原則に従い撤廃。
-- organizations は論理削除（deleted_at）で管理されており、物理削除は発生しない。
-- CASCADE 撤廃による孤児行の発生は理論上ない。
--
-- 将来の organization 単位シャーディング・マイクロサービス分割時に
-- FK 境界をまたぐ制約が障壁になるため今のうちに撤廃する。
--
-- ━━━ 対象一覧（11件）━━━
--
--   chat ドメイン:
--     chat_channels.fk_channel_org           (chat → organization, ON DELETE CASCADE)
--
--   timetable ドメイン:
--     timetable_period_templates.fk_tpt_organization  (timetable → organization, ON DELETE CASCADE)
--     timetable_terms.fk_timetable_term_org           (timetable → organization, ON DELETE CASCADE)
--
--   schedule ドメイン:
--     schedule_event_categories.fk_sec_organization   (schedule → organization, ON DELETE CASCADE)
--
--   shift ドメイン:
--     shift_budget_allocations.fk_sba_organization    (shift → organization, ON DELETE CASCADE)
--     shift_budget_failed_events.fk_sbfe_organization (shift → organization, ON DELETE CASCADE)
--
--   advertising ドメイン:
--     ad_campaigns.fk_ad_campaigns_org                (advertising → organization, no action)
--     advertiser_accounts.fk_advertiser_accounts_organization (advertising → organization, no action)
--
--   proxy ドメイン:
--     proxy_input_consents.fk_pic_org                 (proxy → organization, no action)
--
--   role ドメイン:
--     user_roles.fk_user_roles_org                    (role → organization, no action)
--     permission_groups.fk_permission_groups_org      (role → organization, no action)
--
-- ━━━ index 状況 ━━━
--
-- 既存カバー済み（追加不要）:
--   chat_channels              : INDEX idx_channel_org(organization_id, is_archived, last_message_at DESC) 既存
--   timetable_period_templates : INDEX idx_tpt_org(organization_id) 既存
--   timetable_terms            : INDEX idx_tt_org_year(organization_id, academic_year) 既存
--   schedule_event_categories  : INDEX idx_sec_org(organization_id) 既存
--   shift_budget_allocations   : INDEX idx_sba_org_period(organization_id, period_start, period_end) 既存
--   shift_budget_failed_events : INDEX idx_sbfe_org_status(organization_id, status) 既存
--   proxy_input_consents       : INDEX idx_pic_org(organization_id, effective_until) 既存
--   user_roles                 : INDEX idx_user_roles_org(organization_id) 既存
--
-- index 追加が必要なもの:
--   advertiser_accounts : organization_id に index なし（status でのみ INDEX あり）→ idx 追加
--   permission_groups   : organization_id に index なし（FK のみ）→ idx 追加

-- ===== chat ドメイン =====
-- chat_channels.fk_channel_org
-- organization_id は INDEX idx_channel_org(organization_id, ...) でカバー済 → 追加不要
ALTER TABLE chat_channels DROP FOREIGN KEY fk_channel_org;

-- ===== timetable ドメイン =====
-- timetable_period_templates.fk_tpt_organization
-- organization_id は INDEX idx_tpt_org(organization_id) でカバー済 → 追加不要
ALTER TABLE timetable_period_templates DROP FOREIGN KEY fk_tpt_organization;

-- timetable_terms.fk_timetable_term_org
-- organization_id は INDEX idx_tt_org_year(organization_id, academic_year) でカバー済 → 追加不要
ALTER TABLE timetable_terms DROP FOREIGN KEY fk_timetable_term_org;

-- ===== schedule ドメイン =====
-- schedule_event_categories.fk_sec_organization
-- organization_id は INDEX idx_sec_org(organization_id) でカバー済 → 追加不要
ALTER TABLE schedule_event_categories DROP FOREIGN KEY fk_sec_organization;

-- ===== shift ドメイン =====
-- shift_budget_allocations.fk_sba_organization
-- organization_id は INDEX idx_sba_org_period(organization_id, period_start, period_end) でカバー済 → 追加不要
ALTER TABLE shift_budget_allocations DROP FOREIGN KEY fk_sba_organization;

-- shift_budget_failed_events.fk_sbfe_organization
-- organization_id は INDEX idx_sbfe_org_status(organization_id, status) でカバー済 → 追加不要
ALTER TABLE shift_budget_failed_events DROP FOREIGN KEY fk_sbfe_organization;

-- ===== advertising ドメイン =====
-- ad_campaigns.fk_ad_campaigns_org
-- organization_id は INDEX idx_org_status(advertiser_organization_id, status) でカバー済 → 追加不要
-- ※ カラム名が advertiser_organization_id のため制約名のみ削除
ALTER TABLE ad_campaigns DROP FOREIGN KEY fk_ad_campaigns_org;

-- advertiser_accounts.fk_advertiser_accounts_organization
-- organization_id に organization_id 先頭の index がない → 追加
ALTER TABLE advertiser_accounts DROP FOREIGN KEY fk_advertiser_accounts_organization;
CREATE INDEX idx_advertiser_accounts_org ON advertiser_accounts (organization_id);

-- ===== proxy ドメイン =====
-- proxy_input_consents.fk_pic_org
-- organization_id は INDEX idx_pic_org(organization_id, effective_until) でカバー済 → 追加不要
ALTER TABLE proxy_input_consents DROP FOREIGN KEY fk_pic_org;

-- ===== role ドメイン =====
-- user_roles.fk_user_roles_org
-- organization_id は INDEX idx_user_roles_org(organization_id) でカバー済 → 追加不要
ALTER TABLE user_roles DROP FOREIGN KEY fk_user_roles_org;

-- permission_groups.fk_permission_groups_org
-- organization_id に index がない（FK 制約のみ）→ 追加
ALTER TABLE permission_groups DROP FOREIGN KEY fk_permission_groups_org;
CREATE INDEX idx_permission_groups_org ON permission_groups (organization_id);
