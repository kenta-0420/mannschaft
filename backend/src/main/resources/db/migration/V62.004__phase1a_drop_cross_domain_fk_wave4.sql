-- Phase 1-A wave4: クロスドメインFK撤廃（user_id残件6件 + team_id第二波7件）
--
-- 1000万ユーザー耐久DB再構築 Phase 1-A 第四波。
-- wave1（V62.001: gamification/contact 9件）、wave2（V62.002: admin/onboarding等 9件）、
-- wave3（V62.003: user_id残件7件 + team_id第一波5件）に続き、
-- user_id 残件と team_id 第二波を処理する。
--
-- CLAUDE.md §1「クロスドメインFKは作らない」原則に従い撤廃。
-- 物理削除経路は存在しない（論理削除徹底・UserEntity.anonymize() 済）ため
-- CASCADE 撤廃による孤児行の発生は理論上ない。
--
-- ━━━ user_id 参照（6件）━━━
--
--   safety ドメイン:
--     safety_responses.fk_safety_resp_user         (ON DELETE CASCADE)
--
--   schedule ドメイン:
--     schedule_attendances.fk_sa_user              (ON DELETE CASCADE)
--
--   shift ドメイン:
--     shift_requests.fk_sr_user                   (ON DELETE CASCADE)
--
--   member ドメイン:
--     member_cards.fk_mc_user                     (ON DELETE CASCADE)
--
--   visibility ドメイン:
--     visibility_templates.fk_vt_owner            (ON DELETE CASCADE)
--
--   notification ドメイン:
--     confirmable_notification_recipients.fk_cnr_user (ON DELETE CASCADE)
--
-- ━━━ team_id 参照（7件）━━━
--
--   timetable ドメイン:
--     timetables.fk_tm_team                       (ON DELETE CASCADE)
--
--   schedule ドメイン:
--     schedules.fk_sch_team                       (ON DELETE CASCADE)
--
--   shift ドメイン:
--     shift_schedules.fk_ss_team                  (ON DELETE CASCADE)
--     shift_positions.fk_sp_team                  (ON DELETE CASCADE)
--
--   storage ドメイン:
--     shared_folders.fk_shared_folders_team       (ON DELETE CASCADE)
--
--   blog ドメイン:
--     blog_posts.fk_bp_team                       (ON DELETE CASCADE)
--     blog_post_series.fk_bps_team                (ON DELETE CASCADE)
--
-- index 状況（既存カバー済み → 追加不要のもの）:
--   schedule_attendances     : INDEX idx_sa_user_id(user_id) 既存 → 不要
--   member_cards             : UNIQUE KEY uq_mc_user_scope_active(user_id,...) 先頭カラム → 不要
--   visibility_templates     : INDEX idx_vt_owner(owner_user_id) 既存 → 不要
--   confirmable_notification_recipients : INDEX idx_cnr_user_unconfirmed(user_id,...) 既存 → 不要
--   timetables               : INDEX idx_tm_team_term(team_id,...) 既存 → 不要
--   schedules                : INDEX idx_sch_team_start(team_id,...) 既存 → 不要
--   shift_schedules          : INDEX idx_ss_team_start(team_id,...) 既存 → 不要
--   shift_positions          : UNIQUE KEY uq_sp_team_name(team_id,...) 先頭カラム → 不要
--   blog_posts               : INDEX idx_bp_team_status(team_id,...) 既存 → 不要
--   blog_post_series         : INDEX idx_bps_team(team_id) 既存 → 不要
--
-- index 追加が必要なもの:
--   safety_responses         : UNIQUE KEY uq_sr_check_user(safety_check_id, user_id) の第2列
--                              → user_id 単独検索不可 → idx 追加
--   shift_requests           : INDEX idx_sr_schedule_user(schedule_id, user_id) の第2列
--                              → user_id 単独検索不可 → idx 追加
--   shared_folders           : team_id 用インデックスなし → idx 追加

-- ===== safety ドメイン =====
-- safety_responses.fk_safety_resp_user
-- UNIQUE KEY uq_sr_check_user(safety_check_id, user_id) の第2列なので user_id 単独検索不可 →
-- user_id 単独検索用 index を追加
ALTER TABLE safety_responses DROP FOREIGN KEY fk_safety_resp_user;
CREATE INDEX idx_safety_resp_user_id ON safety_responses (user_id);

-- ===== schedule ドメイン（user_id）=====
-- schedule_attendances.fk_sa_user
-- user_id は INDEX idx_sa_user_id(user_id) でカバー済 → 追加不要
ALTER TABLE schedule_attendances DROP FOREIGN KEY fk_sa_user;

-- ===== shift ドメイン（user_id）=====
-- shift_requests.fk_sr_user
-- INDEX idx_sr_schedule_user(schedule_id, user_id) の第2列なので user_id 単独検索不可 →
-- user_id 単独検索用 index を追加
ALTER TABLE shift_requests DROP FOREIGN KEY fk_sr_user;
CREATE INDEX idx_shift_requests_user_id ON shift_requests (user_id);

-- ===== member ドメイン =====
-- member_cards.fk_mc_user
-- UNIQUE KEY uq_mc_user_scope_active(user_id, scope_type, scope_id, active_unique_key) の先頭カラム
-- → user_id 単独検索可能 → 追加不要
ALTER TABLE member_cards DROP FOREIGN KEY fk_mc_user;

-- ===== visibility ドメイン =====
-- visibility_templates.fk_vt_owner
-- owner_user_id は INDEX idx_vt_owner(owner_user_id) でカバー済 → 追加不要
ALTER TABLE visibility_templates DROP FOREIGN KEY fk_vt_owner;

-- ===== notification ドメイン =====
-- confirmable_notification_recipients.fk_cnr_user
-- user_id は INDEX idx_cnr_user_unconfirmed(user_id, is_confirmed, created_at DESC) でカバー済 → 追加不要
ALTER TABLE confirmable_notification_recipients DROP FOREIGN KEY fk_cnr_user;

-- ===== timetable ドメイン（team_id）=====
-- timetables.fk_tm_team
-- team_id は INDEX idx_tm_team_term(team_id, term_id) でカバー済 → 追加不要
ALTER TABLE timetables DROP FOREIGN KEY fk_tm_team;

-- ===== schedule ドメイン（team_id）=====
-- schedules.fk_sch_team
-- team_id は INDEX idx_sch_team_start(team_id, start_at) でカバー済 → 追加不要
ALTER TABLE schedules DROP FOREIGN KEY fk_sch_team;

-- ===== shift ドメイン（team_id）=====
-- shift_schedules.fk_ss_team
-- team_id は INDEX idx_ss_team_start(team_id, start_date DESC) でカバー済 → 追加不要
ALTER TABLE shift_schedules DROP FOREIGN KEY fk_ss_team;

-- shift_positions.fk_sp_team
-- team_id は UNIQUE KEY uq_sp_team_name(team_id, name) の先頭カラム → 追加不要
ALTER TABLE shift_positions DROP FOREIGN KEY fk_sp_team;

-- ===== storage ドメイン =====
-- shared_folders.fk_shared_folders_team
-- team_id 用インデックスなし → team_id 単独検索用 index を追加
ALTER TABLE shared_folders DROP FOREIGN KEY fk_shared_folders_team;
CREATE INDEX idx_shared_folders_team_id ON shared_folders (team_id);

-- ===== blog ドメイン =====
-- blog_posts.fk_bp_team
-- team_id は INDEX idx_bp_team_status(team_id, status, published_at) でカバー済 → 追加不要
ALTER TABLE blog_posts DROP FOREIGN KEY fk_bp_team;

-- blog_post_series.fk_bps_team
-- team_id は INDEX idx_bps_team(team_id) でカバー済 → 追加不要
ALTER TABLE blog_post_series DROP FOREIGN KEY fk_bps_team;
