-- Phase 1-A wave3: クロスドメインFK撤廃（user_id残件7件 + team_id第一波5件）
--
-- 1000万ユーザー耐久DB再構築 Phase 1-A 第三波。
-- wave1（V62.001: gamification/contact 9件）、wave2（V62.002: admin/onboarding等 9件）に続き、
-- user_id 残件と team_id 第一波を処理する。
--
-- CLAUDE.md §1「クロスドメインFKは作らない」原則に従い撤廃。
-- 物理削除経路は存在しない（論理削除徹底・UserEntity.anonymize() 済）ため
-- CASCADE 撤廃による孤児行の発生は理論上ない。
--
-- ━━━ user_id 参照（7件）━━━
--
--   knowledge ドメイン:
--     kb_page_favorites.fk_kbpf_user            (ON DELETE CASCADE)
--
--   calendar ドメイン:
--     user_google_calendar_connections.fk_ugcc_user   (ON DELETE CASCADE)
--     user_calendar_sync_settings.fk_ucss_user        (ON DELETE CASCADE)
--     user_ical_tokens.fk_uit_user                    (ON DELETE CASCADE)
--
--   announcement ドメイン:
--     announcement_read_status.fk_ars_user       (ON DELETE CASCADE)
--
--   event ドメイン:
--     event_rsvp_responses.fk_rsvp_user          (ON DELETE CASCADE)
--
--   timetable ドメイン:
--     personal_timetable_settings.fk_pts_settings_user (ON DELETE CASCADE)
--
-- ━━━ team_id 参照（5件）━━━
--
--   chat ドメイン:
--     chat_channels.fk_channel_team              (ON DELETE CASCADE)
--
--   timetable ドメイン:
--     timetable_terms.fk_tt_team                 (ON DELETE CASCADE)
--     personal_timetable_share_targets.fk_ptst_team (ON DELETE CASCADE)
--
--   schedule ドメイン:
--     schedule_event_categories.fk_sec_team      (ON DELETE CASCADE)
--
--   jobmatching ドメイン:
--     job_postings.fk_jp_team                    (ON DELETE CASCADE)
--
-- index 状況（既存カバー済み → 追加不要のもの）:
--   kb_page_favorites          : INDEX idx_kbpf_user(user_id) 既存 → 不要
--   user_google_calendar_connections : UNIQUE KEY uq_ugcc_user(user_id) 既存 → 不要
--   user_calendar_sync_settings: UNIQUE KEY uq_ucss_user_scope(user_id,...) 既存 → 不要
--   user_ical_tokens           : UNIQUE KEY uq_uit_user(user_id) 既存 → 不要
--   announcement_read_status   : INDEX idx_ars_user(user_id, read_at DESC) 既存 → 不要
--   personal_timetable_settings: PRIMARY KEY (user_id) → 不要
--   chat_channels              : INDEX idx_channel_team(team_id,...) 既存 → 不要
--   timetable_terms            : INDEX idx_tt_team_year(team_id,...) 既存 → 不要
--   personal_timetable_share_targets: INDEX idx_ptst_share_team(team_id) 既存 → 不要
--   schedule_event_categories  : INDEX idx_sec_team(team_id) 既存 → 不要
--   job_postings               : INDEX idx_jp_team_status(team_id,...) 既存 → 不要
--
-- index 追加が必要なもの:
--   event_rsvp_responses: UNIQUE KEY uq_rsvp_event_user(event_id, user_id) のみ
--   　　　　　　　　　　　→ user_id 先頭でないため単独検索不可 → idx 追加

-- ===== knowledge ドメイン =====
-- kb_page_favorites.fk_kbpf_user
-- user_id は INDEX idx_kbpf_user(user_id) でカバー済 → 追加不要
ALTER TABLE kb_page_favorites DROP FOREIGN KEY fk_kbpf_user;

-- ===== calendar ドメイン =====
-- user_google_calendar_connections.fk_ugcc_user
-- user_id は UNIQUE KEY uq_ugcc_user(user_id) でカバー済 → 追加不要
ALTER TABLE user_google_calendar_connections DROP FOREIGN KEY fk_ugcc_user;

-- user_calendar_sync_settings.fk_ucss_user
-- user_id は UNIQUE KEY uq_ucss_user_scope(user_id, scope_type, scope_id) でカバー済 → 追加不要
ALTER TABLE user_calendar_sync_settings DROP FOREIGN KEY fk_ucss_user;

-- user_ical_tokens.fk_uit_user
-- user_id は UNIQUE KEY uq_uit_user(user_id) でカバー済 → 追加不要
ALTER TABLE user_ical_tokens DROP FOREIGN KEY fk_uit_user;

-- ===== announcement ドメイン =====
-- announcement_read_status.fk_ars_user
-- user_id は INDEX idx_ars_user(user_id, read_at DESC) でカバー済 → 追加不要
ALTER TABLE announcement_read_status DROP FOREIGN KEY fk_ars_user;

-- ===== event ドメイン =====
-- event_rsvp_responses.fk_rsvp_user
-- UNIQUE KEY uq_rsvp_event_user(event_id, user_id) の第2列なので user_id 単独検索不可 →
-- user_id 単独検索用 index を追加
ALTER TABLE event_rsvp_responses DROP FOREIGN KEY fk_rsvp_user;
CREATE INDEX idx_rsvp_user_id ON event_rsvp_responses (user_id);

-- ===== timetable ドメイン（user_id）=====
-- personal_timetable_settings.fk_pts_settings_user
-- user_id は PRIMARY KEY でカバー済 → 追加不要
ALTER TABLE personal_timetable_settings DROP FOREIGN KEY fk_pts_settings_user;

-- ===== chat ドメイン =====
-- chat_channels.fk_channel_team
-- team_id は INDEX idx_channel_team(team_id, is_archived, last_message_at DESC) でカバー済 → 追加不要
ALTER TABLE chat_channels DROP FOREIGN KEY fk_channel_team;

-- ===== timetable ドメイン（team_id）=====
-- timetable_terms.fk_tt_team
-- team_id は INDEX idx_tt_team_year(team_id, academic_year) でカバー済 → 追加不要
ALTER TABLE timetable_terms DROP FOREIGN KEY fk_tt_team;

-- personal_timetable_share_targets.fk_ptst_team
-- team_id は INDEX idx_ptst_share_team(team_id) でカバー済 → 追加不要
ALTER TABLE personal_timetable_share_targets DROP FOREIGN KEY fk_ptst_team;

-- ===== schedule ドメイン =====
-- schedule_event_categories.fk_sec_team
-- team_id は INDEX idx_sec_team(team_id) でカバー済 → 追加不要
ALTER TABLE schedule_event_categories DROP FOREIGN KEY fk_sec_team;

-- ===== jobmatching ドメイン =====
-- job_postings.fk_jp_team
-- team_id は INDEX idx_jp_team_status(team_id, status, deleted_at) でカバー済 → 追加不要
ALTER TABLE job_postings DROP FOREIGN KEY fk_jp_team;
