-- F09.8 積み残し件1: corkboard_cards に section_id（primary section）を追加。
--
-- 背景:
--   Phase E までは「カード→セクション」紐付けを corkboard_card_groups (中間テーブル) のみで
--   表現していた。フロント (cardSectionMap) は「直近 API 呼び出し結果を楽観的に保持」する
--   状態で、リロードすると紐付けが消える事象が残っていた。
--
-- 本マイグレーション:
--   primary section（カードが現在所属している主セクション）を corkboard_cards.section_id
--   に正規列として追加し、フロントが card.sectionId を直接読めるようにする。
--   既存の corkboard_card_groups は残し、複数セクション拡張余地を担保する。
--
-- 参照:
--   - 設計書 docs/features/F09.8_corkboard.md（Phase E セクション機能）
--   - 中間テーブル: V9.0xx 系で作成済み corkboard_card_groups
ALTER TABLE corkboard_cards
    ADD COLUMN section_id BIGINT UNSIGNED NULL AFTER corkboard_id,
    ADD CONSTRAINT fk_cc_section FOREIGN KEY (section_id)
        REFERENCES corkboard_groups(id) ON DELETE SET NULL;

CREATE INDEX idx_cc_section ON corkboard_cards (corkboard_id, section_id);
