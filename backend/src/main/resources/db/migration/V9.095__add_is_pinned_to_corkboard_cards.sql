-- F09.8.1: コルクボード・ピン止めカラム追加
-- マイボード上の重要カードをピン止めし、ダッシュボード統合で横断表示するための基盤。
ALTER TABLE corkboard_cards
    ADD COLUMN is_pinned BOOLEAN NOT NULL DEFAULT FALSE AFTER is_archived,
    ADD COLUMN pinned_at DATETIME NULL AFTER is_pinned;

-- ピン止めカード絞り込み専用インデックス（個人ボード横断クエリ用）
CREATE INDEX idx_cc_pinned ON corkboard_cards (corkboard_id, is_pinned, is_archived, deleted_at);

-- ピン止め日時降順ソート用
CREATE INDEX idx_cc_pinned_at ON corkboard_cards (is_pinned, pinned_at DESC);
