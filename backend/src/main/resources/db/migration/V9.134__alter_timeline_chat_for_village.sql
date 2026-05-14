-- F17.1 Phase 1: 既存タイムライン・チャットテーブルへの村機能対応カラム追加
-- scope_type / channel_type は元々 VARCHAR(20) ゆえ MODIFY 不要
-- scope_village_id / village_id への FK は張らない（クロスドメイン参照・原則1）

-- 1. timeline_posts: scope_village_id 追加
-- 注: posted_as_type / posted_as_id は既存（V4.001 で導入済）ゆえ重複追加しない。
--     既存カラムを設計書の posted_as_subject_type / posted_as_subject_id に対応させる。
ALTER TABLE timeline_posts
    ADD COLUMN scope_village_id BINARY(16) NULL COMMENT 'scope_type=VILLAGE のとき村ID（FK 張らない / 原則1）',
    ADD KEY idx_tp_scope_village (scope_village_id);

-- 2. chat_channels: village_id 追加
ALTER TABLE chat_channels
    ADD COLUMN village_id BINARY(16) NULL COMMENT 'channel_type=VILLAGE_LOBBY のとき必須（FK 張らない / 原則1）',
    ADD KEY idx_cc_village (village_id);

-- 3. chat_messages: 投稿主体切替対応
ALTER TABLE chat_messages
    ADD COLUMN posted_as_subject_type VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT '投稿主体種別 USER/TEAM/ORGANIZATION',
    ADD COLUMN posted_as_subject_id BIGINT UNSIGNED NULL COMMENT 'USER 以外の場合の主体ID',
    ADD KEY idx_cm_posted_as (posted_as_subject_type, posted_as_subject_id);
