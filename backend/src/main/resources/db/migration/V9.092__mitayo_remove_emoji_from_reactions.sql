-- F04.1 みたよ！ボタン移行: timeline_post_reactions から emoji 列を削除し UNIQUE キーを変更する
-- 既存の絵文字リアクションデータは移行前に一度クリアする（みたよ！に概念が変わるため）
DELETE FROM timeline_post_reactions;

-- 旧 UNIQUE キー (timeline_post_id, user_id, emoji) を削除
ALTER TABLE timeline_post_reactions
    DROP INDEX uq_tpr_post_user_emoji;

-- emoji 列を削除
ALTER TABLE timeline_post_reactions
    DROP COLUMN emoji;

-- 新 UNIQUE キー (timeline_post_id, user_id) を追加
ALTER TABLE timeline_post_reactions
    ADD CONSTRAINT uq_tpr_post_user UNIQUE (timeline_post_id, user_id);
