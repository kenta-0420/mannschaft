-- Phase 1-A 第一波: クロスドメインFK 撤廃（user_id 参照 9 件）
--
-- 1000万ユーザー耐久DB再構築 Phase 1（ドメイン境界の物理的徹底）の第一波。
-- gamification ドメインと contact ドメインから user ドメイン (users 表) への
-- 越境 FOREIGN KEY を撤廃し、参照整合性はアプリケーション層で保証する方針へ移行する。
--
-- 対象（ON DELETE CASCADE はいずれも撤廃。以後はユーザー退会時の匿名化フローで
-- 履歴は保持する設計に切替済み — UserEntity.anonymize() / UserService.withdrawUser()）。
--
--   gamification:
--     point_transactions.fk_pt_user
--     user_badges.fk_ub_user
--     ranking_snapshots.fk_rs_user
--     gamification_user_settings.fk_gus_user
--
--   contact:
--     contact_requests.fk_cr_requester
--     contact_requests.fk_cr_target
--     contact_request_blocks.fk_crb_user
--     contact_request_blocks.fk_crb_blocked
--     contact_invite_tokens.fk_cit_user
--
-- 既存 index 状況:
--   - point_transactions, user_badges, gamification_user_settings, contact_*
--     は user_id を含む UNIQUE/複合 index で先頭列カバー済 → 追加不要
--   - ranking_snapshots は (scope_type, scope_id, period_type, period_label, user_id)
--     の UNIQUE しか持たず user_id 単独検索が効かない → idx 追加

-- ===== gamification ドメイン =====
ALTER TABLE point_transactions          DROP FOREIGN KEY fk_pt_user;
ALTER TABLE user_badges                 DROP FOREIGN KEY fk_ub_user;
ALTER TABLE ranking_snapshots           DROP FOREIGN KEY fk_rs_user;
ALTER TABLE gamification_user_settings  DROP FOREIGN KEY fk_gus_user;

CREATE INDEX idx_ranking_snapshots_user_id ON ranking_snapshots (user_id);

-- ===== contact ドメイン =====
ALTER TABLE contact_requests        DROP FOREIGN KEY fk_cr_requester;
ALTER TABLE contact_requests        DROP FOREIGN KEY fk_cr_target;
ALTER TABLE contact_request_blocks  DROP FOREIGN KEY fk_crb_user;
ALTER TABLE contact_request_blocks  DROP FOREIGN KEY fk_crb_blocked;
ALTER TABLE contact_invite_tokens   DROP FOREIGN KEY fk_cit_user;
