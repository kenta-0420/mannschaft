-- Phase 1-A 第六波: デッドなクロスドメインFK撤廃（team/org親 + notification user CASCADE）21件
--
-- 1000万ユーザー耐久DB再構築 Phase 1-A。
-- CLAUDE.md §1「クロスドメインFKは作らない」原則に従い撤廃。
--
-- ━━━ なぜ安全か ━━━
--
-- 【team/organization 親FK（18件）が発火不能な理由】
--   teams・organizations は論理削除（deleted_at）で管理されており、
--   物理削除は AccountPurgeService のユーザー匿名化経路のみ（team/org は削除しない）。
--   すなわち ON DELETE CASCADE / SET NULL が永久に発火しないデッドコードとなっている。
--   InnoDB は FK チェック（INSERT/UPDATE 時の参照整合性検証）のコストを毎回払っているが、
--   発火しない制約コストを払い続ける価値はなく、今撤廃しておくことで
--   将来の team/organization 単位シャーディング・マイクロサービス分割時の障壁を除去する。
--
-- 【notification user CASCADE（19〜21: 3件）が冗長な理由】
--   NotificationAnonymizationEventListener（UserAnonymizedEvent 受信）が
--   退会時に push_subscriptions / notification_preferences / notification_type_preferences を
--   先行削除（deleteByUserId）する実装が既に存在する。
--   したがって users 物理削除前に notification 側レコードは既に存在しないため
--   CASCADE は冗長 = 安全に撤廃できる。
--   (参照: backend/.../notification/event/NotificationAnonymizationEventListener.java)
--
-- ━━━ index 状況（撤廃後もバッキングインデックスは残るが、独立インデックスとして要確認）━━━
--
-- 既存カバー済み（追加不要）:
--   action_memos.posted_team_id           : INDEX idx_am_posted_team (posted_team_id, memo_date) 既存
--   blog_post_series.organization_id      : INDEX idx_bps_org (organization_id) 既存
--   blog_posts.organization_id            : INDEX idx_bp_org_status (organization_id, ...) 既存
--   dwelling_units.team_id                : INDEX idx_du_scope (scope_type, team_id, ...) 既存
--   dwelling_units.organization_id        : INDEX idx_du_scope (..., organization_id, ...) 既存
--   emergency_closures.team_id            : INDEX idx_emergency_closures_team_date (team_id, ...) 既存
--   reservation_business_hours.team_id    : UNIQUE KEY uk_reservation_bh_team_day (team_id, ...) 既存
--   reservation_slots.team_id             : INDEX idx_reservation_slots_team_date (team_id, ...) 既存
--   reservations.team_id                  : INDEX idx_reservations_team_status_booked (team_id, ...) 既存
--   schedules.organization_id             : INDEX idx_sch_org_start (organization_id, ...) 既存
--   shift_budget_allocations.team_id      : INDEX idx_sba_team_period (team_id, ...) 既存
--   notification_preferences.user_id      : UNIQUE KEY uq_notification_preferences_user_scope (user_id, ...) 既存
--   notification_type_preferences.user_id : UNIQUE KEY uq_notification_type_preferences_user_type (user_id, ...) 既存
--
-- index 追加が必要なもの（FK バッキングインデックスが独立インデックスとして存在しない）:
--   action_memos.organization_id               : FKのみ → idx 追加
--   error_reports.organization_id              : FKのみ → idx 追加
--   recruitment_cancellation_records.team_id   : FKのみ → idx 追加
--   reservation_lines.team_id                  : FKのみ → idx 追加
--   reservation_blocked_times.team_id          : FKのみ → idx 追加
--   shared_folders.organization_id             : FKのみ → idx 追加
--   user_action_memo_settings.default_post_team_id : FKのみ → idx 追加
--   push_subscriptions.user_id                 : FKのみ → idx 追加
--
-- ━━━ 対象一覧（21件）━━━
--
-- team/organization 親（発火不能デッドコード）:
--  1. action_memos / fk_action_memos_organization (→organizations SET NULL)
--  2. action_memos / fk_am_posted_team            (→teams SET NULL)
--  3. blog_post_series / fk_bps_org               (→organizations CASCADE)
--  4. blog_posts / fk_bp_org                      (→organizations CASCADE)
--  5. dwelling_units / fk_du_organization          (→organizations CASCADE)
--  6. dwelling_units / fk_du_team                 (→teams CASCADE)
--  7. emergency_closures / fk_emergency_closures_team (→teams CASCADE)
--  8. error_reports / fk_error_reports_organization_id (→organizations SET NULL)
--  9. recruitment_cancellation_records / fk_rcr_team   (→teams SET NULL)
-- 10. reservation_blocked_times / fk_reservation_bt_team  (→teams CASCADE)
-- 11. reservation_business_hours / fk_reservation_bh_team (→teams CASCADE)
-- 12. reservation_lines / fk_reservation_lines_team        (→teams CASCADE)
-- 13. reservation_slots / fk_reservation_slots_team        (→teams CASCADE)
-- 14. reservations / fk_reservations_team                  (→teams CASCADE)
-- 15. shared_folders / fk_shared_folders_org               (→organizations CASCADE)
-- 16. schedules / fk_sch_org                               (→organizations CASCADE)
-- 17. shift_budget_allocations / fk_sba_team               (→teams CASCADE)
-- 18. user_action_memo_settings / fk_uams_default_team     (→teams SET NULL)
-- notification user CASCADE（リスナー先行削除で冗長）:
-- 19. push_subscriptions / fk_push_subscriptions_user           (→users CASCADE)
-- 20. notification_preferences / fk_notification_preferences_user (→users CASCADE)
-- 21. notification_type_preferences / fk_notification_type_preferences_user (→users CASCADE)

-- ===== action_memos =====
-- fk_action_memos_organization: organization_id → organizations (SET NULL)
-- → organizations 論理削除のみ・物理削除なし → 発火不能
-- organization_id に独立インデックスなし → 追加
ALTER TABLE action_memos DROP FOREIGN KEY fk_action_memos_organization;
CREATE INDEX idx_am_organization ON action_memos (organization_id);

-- fk_am_posted_team: posted_team_id → teams (SET NULL)
-- → teams 論理削除のみ・物理削除なし → 発火不能
-- INDEX idx_am_posted_team (posted_team_id, memo_date) 既存 → 追加不要
ALTER TABLE action_memos DROP FOREIGN KEY fk_am_posted_team;

-- ===== blog_post_series =====
-- fk_bps_org: organization_id → organizations (CASCADE)
-- → organizations 論理削除のみ・物理削除なし → 発火不能
-- INDEX idx_bps_org (organization_id) 既存 → 追加不要
ALTER TABLE blog_post_series DROP FOREIGN KEY fk_bps_org;

-- ===== blog_posts =====
-- fk_bp_org: organization_id → organizations (CASCADE)
-- → organizations 論理削除のみ・物理削除なし → 発火不能
-- INDEX idx_bp_org_status (organization_id, ...) 既存 → 追加不要
ALTER TABLE blog_posts DROP FOREIGN KEY fk_bp_org;

-- ===== dwelling_units =====
-- fk_du_organization: organization_id → organizations (CASCADE)
-- → organizations 論理削除のみ・物理削除なし → 発火不能
-- INDEX idx_du_scope (..., organization_id, ...) 既存 → 追加不要
ALTER TABLE dwelling_units DROP FOREIGN KEY fk_du_organization;

-- fk_du_team: team_id → teams (CASCADE)
-- → teams 論理削除のみ・物理削除なし → 発火不能
-- INDEX idx_du_scope (scope_type, team_id, ...) 既存 → 追加不要
ALTER TABLE dwelling_units DROP FOREIGN KEY fk_du_team;

-- ===== emergency_closures =====
-- fk_emergency_closures_team: team_id → teams (CASCADE)
-- → teams 論理削除のみ・物理削除なし → 発火不能
-- INDEX idx_emergency_closures_team_date (team_id, ...) 既存 → 追加不要
ALTER TABLE emergency_closures DROP FOREIGN KEY fk_emergency_closures_team;

-- ===== error_reports =====
-- fk_error_reports_organization_id: organization_id → organizations (SET NULL)
-- → organizations 論理削除のみ・物理削除なし → 発火不能
-- organization_id に独立インデックスなし → 追加
ALTER TABLE error_reports DROP FOREIGN KEY fk_error_reports_organization_id;
CREATE INDEX idx_error_reports_organization ON error_reports (organization_id);

-- ===== recruitment_cancellation_records =====
-- fk_rcr_team: team_id → teams (SET NULL)
-- → teams 論理削除のみ・物理削除なし → 発火不能
-- team_id に独立インデックスなし → 追加
ALTER TABLE recruitment_cancellation_records DROP FOREIGN KEY fk_rcr_team;
CREATE INDEX idx_rcr_team ON recruitment_cancellation_records (team_id);

-- ===== reservation_blocked_times =====
-- fk_reservation_bt_team: team_id → teams (CASCADE)
-- → teams 論理削除のみ・物理削除なし → 発火不能
-- team_id に独立インデックスなし → 追加
ALTER TABLE reservation_blocked_times DROP FOREIGN KEY fk_reservation_bt_team;
CREATE INDEX idx_reservation_bt_team ON reservation_blocked_times (team_id);

-- ===== reservation_business_hours =====
-- fk_reservation_bh_team: team_id → teams (CASCADE)
-- → teams 論理削除のみ・物理削除なし → 発火不能
-- UNIQUE KEY uk_reservation_bh_team_day (team_id, day_of_week) 既存 → 追加不要
ALTER TABLE reservation_business_hours DROP FOREIGN KEY fk_reservation_bh_team;

-- ===== reservation_lines =====
-- fk_reservation_lines_team: team_id → teams (CASCADE)
-- → teams 論理削除のみ・物理削除なし → 発火不能
-- team_id に独立インデックスなし → 追加
ALTER TABLE reservation_lines DROP FOREIGN KEY fk_reservation_lines_team;
CREATE INDEX idx_reservation_lines_team ON reservation_lines (team_id);

-- ===== reservation_slots =====
-- fk_reservation_slots_team: team_id → teams (CASCADE)
-- → teams 論理削除のみ・物理削除なし → 発火不能
-- INDEX idx_reservation_slots_team_date (team_id, ...) 既存 → 追加不要
ALTER TABLE reservation_slots DROP FOREIGN KEY fk_reservation_slots_team;

-- ===== reservations =====
-- fk_reservations_team: team_id → teams (CASCADE)
-- → teams 論理削除のみ・物理削除なし → 発火不能
-- INDEX idx_reservations_team_status_booked (team_id, ...) 既存 → 追加不要
ALTER TABLE reservations DROP FOREIGN KEY fk_reservations_team;

-- ===== shared_folders =====
-- fk_shared_folders_org: organization_id → organizations (CASCADE)
-- → organizations 論理削除のみ・物理削除なし → 発火不能
-- organization_id に独立インデックスなし → 追加
ALTER TABLE shared_folders DROP FOREIGN KEY fk_shared_folders_org;
CREATE INDEX idx_shared_folders_org ON shared_folders (organization_id);

-- ===== schedules =====
-- fk_sch_org: organization_id → organizations (CASCADE)
-- → organizations 論理削除のみ・物理削除なし → 発火不能
-- INDEX idx_sch_org_start (organization_id, ...) 既存 → 追加不要
ALTER TABLE schedules DROP FOREIGN KEY fk_sch_org;

-- ===== shift_budget_allocations =====
-- fk_sba_team: team_id → teams (CASCADE)
-- → teams 論理削除のみ・物理削除なし → 発火不能
-- INDEX idx_sba_team_period (team_id, ...) 既存 → 追加不要
ALTER TABLE shift_budget_allocations DROP FOREIGN KEY fk_sba_team;

-- ===== user_action_memo_settings =====
-- fk_uams_default_team: default_post_team_id → teams (SET NULL)
-- → teams 論理削除のみ・物理削除なし → 発火不能
-- default_post_team_id に独立インデックスなし → 追加
ALTER TABLE user_action_memo_settings DROP FOREIGN KEY fk_uams_default_team;
CREATE INDEX idx_uams_default_team ON user_action_memo_settings (default_post_team_id);

-- ===== push_subscriptions =====
-- fk_push_subscriptions_user: user_id → users (CASCADE)
-- → NotificationAnonymizationEventListener が UserAnonymizedEvent 受信時に
--   deleteByUserId で先行削除するため CASCADE は冗長（実質発火しない）
-- user_id に独立インデックスなし → 追加
ALTER TABLE push_subscriptions DROP FOREIGN KEY fk_push_subscriptions_user;
CREATE INDEX idx_push_subscriptions_user ON push_subscriptions (user_id);

-- ===== notification_preferences =====
-- fk_notification_preferences_user: user_id → users (CASCADE)
-- → NotificationAnonymizationEventListener が先行削除 → CASCADE 冗長
-- UNIQUE KEY uq_notification_preferences_user_scope (user_id, ...) 既存 → 追加不要
ALTER TABLE notification_preferences DROP FOREIGN KEY fk_notification_preferences_user;

-- ===== notification_type_preferences =====
-- fk_notification_type_preferences_user: user_id → users (CASCADE)
-- → NotificationAnonymizationEventListener が先行削除 → CASCADE 冗長
-- UNIQUE KEY uq_notification_type_preferences_user_type (user_id, ...) 既存 → 追加不要
ALTER TABLE notification_type_preferences DROP FOREIGN KEY fk_notification_type_preferences_user;
