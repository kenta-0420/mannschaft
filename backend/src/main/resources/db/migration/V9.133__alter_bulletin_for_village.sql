-- F17.1 Phase 1: 既存掲示板テーブルへの村機能対応カラム追加
-- scope_type は元々 VARCHAR(20) ゆえ MODIFY 不要（'VILLAGE' 値はそのまま投入可）
-- scope_village_id への FK は張らない（クロスドメイン参照・原則1）
--
-- 注: 設計書 §3.12.1 の "bulletin_posts" 表記は実コードベースには存在せず、
--     掲示板の投稿主体は bulletin_threads（スレッド本体）と bulletin_replies（返信）の二系統である。
--     両方に posted_as_subject_type / posted_as_subject_id を追加して投稿主体切替を実現する。

-- 1. bulletin_threads
ALTER TABLE bulletin_threads
    ADD COLUMN scope_village_id BINARY(16) NULL COMMENT 'scope_type=VILLAGE のとき村ID（FK 張らない / 原則1）' AFTER scope_id,
    ADD COLUMN posted_as_subject_type VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT '投稿主体種別 USER/TEAM/ORGANIZATION' AFTER author_id,
    ADD COLUMN posted_as_subject_id BIGINT UNSIGNED NULL COMMENT 'USER 以外の場合の主体ID' AFTER posted_as_subject_type,
    ADD KEY idx_bt_scope_village (scope_village_id),
    ADD KEY idx_bt_posted_as (posted_as_subject_type, posted_as_subject_id);

-- 2. bulletin_replies（返信側にも投稿主体を持たせる）
ALTER TABLE bulletin_replies
    ADD COLUMN posted_as_subject_type VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT '投稿主体種別 USER/TEAM/ORGANIZATION',
    ADD COLUMN posted_as_subject_id BIGINT UNSIGNED NULL COMMENT 'USER 以外の場合の主体ID',
    ADD KEY idx_br_posted_as (posted_as_subject_type, posted_as_subject_id);
