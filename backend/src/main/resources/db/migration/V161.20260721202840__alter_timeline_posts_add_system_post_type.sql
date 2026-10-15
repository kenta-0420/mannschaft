-- F17.2 Wave2 ①行事→村フィード自動還流: timeline_posts へのシステム投稿対応 ALTER
-- （timeline ドメインの変更・設計書 §3.2）
--
-- システム名義投稿（投稿者ユーザー不在）を表現するため:
--   1. user_id を NULL 許容へ緩める（システム投稿は user_id IS NULL）
--      - 既存 FK fk_timeline_posts_user（ON DELETE CASCADE）はそのまま残す。MySQL の FK は
--        参照列が NULL の行を制約対象外にするため「NULL 許容 FK」として有効（DROP/張り替え不要）。
--      - 既存行はすべて user_id 非 NULL のまま（後方互換・Expand 方針）。
--   2. system_post_type: NULL=通常投稿 / 非NULL=システム自動投稿の種別
--      （村ドメイン enum VillageEventNotificationType の .name() を格納）
--   3. source_event_uuid: システム投稿の対象行事 UUID（歳時記/祭/寄合の id・FK非付与・原則1）
--   4. idx_timeline_posts_sys: EVENT_UPCOMING の冪等判定・行事別集約を index だけで完結させる（§3.7）

ALTER TABLE timeline_posts
    MODIFY COLUMN user_id BIGINT UNSIGNED NULL COMMENT 'システム投稿は投稿者ユーザー不在（NULL）',
    ADD COLUMN system_post_type  VARCHAR(40) NULL COMMENT 'NULL=通常投稿 / 非NULL=システム自動投稿の種別（VillageEventNotificationType.name()）',
    ADD COLUMN source_event_uuid BINARY(16)  NULL COMMENT 'システム投稿の対象行事UUID（FK非付与・原則1）。通常投稿はNULL',
    ADD INDEX idx_timeline_posts_sys (scope_village_id, system_post_type, source_event_uuid);
