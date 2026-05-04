-- F09.8 Phase A: デッドリファレンス検知バッチ用カラム追加
-- 参照先（reference_type + reference_id）が論理削除された場合に true となる。
-- フロントエンドは一覧段階で「元コンテンツ削除済み」バッジを表示する。
ALTER TABLE corkboard_cards
    ADD COLUMN is_ref_deleted BOOLEAN NOT NULL DEFAULT FALSE AFTER is_pinned;

-- デッドリファレンス検知バッチ用インデックス
-- (card_type, is_ref_deleted, deleted_at) で REFERENCE カードのうち未検知のものを高速抽出
CREATE INDEX idx_cc_ref_deleted ON corkboard_cards (card_type, is_ref_deleted, deleted_at);
