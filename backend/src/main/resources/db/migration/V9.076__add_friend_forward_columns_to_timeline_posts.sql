-- V9.076: timeline_posts に F01.5 フレンドチーム連携カラムを追加
-- share_with_friends は Phase 1 から即利用。
-- forward_source_post_id / forward_target_range は Phase 3 から利用（Phase 1 では NULL 許容のまま）。
--
-- 依存テーブル: timeline_posts (V4.001)
-- 設計書: docs/features/F01.5_team_friend_relationships.md §10.1 (F04.1 拡張), §12

-- ---------------------------------------------------------------------------
-- カラム追加
-- ---------------------------------------------------------------------------
-- NOTE:
--   既存 timeline_posts.scope_type は VARCHAR(20) のため、FRIEND_TEAM / FRIEND_FORWARD /
--   FRIEND_ARCHIVE 値の追加に DDL 変更は不要。アプリ層 enum の拡張のみで対応する。
ALTER TABLE timeline_posts
    ADD COLUMN share_with_friends     BOOLEAN         NOT NULL DEFAULT FALSE
        COMMENT 'フレンドチームへの配信許可（Phase 1 から有効）',
    ADD COLUMN forward_source_post_id BIGINT UNSIGNED NULL
        COMMENT 'FK -> timeline_posts.id（転送元投稿・Phase 3 利用）',
    ADD COLUMN forward_target_range   VARCHAR(30)     NULL
        COMMENT 'MEMBER / MEMBER_AND_SUPPORTER（Phase 3 利用）',
    ADD INDEX idx_tp_share_friends  (share_with_friends, scope_type, scope_id),
    ADD INDEX idx_tp_forward_source (forward_source_post_id);

-- FK 制約は forward_source_post_id のみ（転送連鎖用）。
-- 転送元投稿が物理削除された場合、forward_source_post_id を NULL にリセットし、
-- 受信側 timeline_posts は残したまま Service 層のフックで論理削除を検討できる形にする。
ALTER TABLE timeline_posts
    ADD CONSTRAINT fk_tp_forward_source FOREIGN KEY (forward_source_post_id)
        REFERENCES timeline_posts (id) ON DELETE SET NULL;
