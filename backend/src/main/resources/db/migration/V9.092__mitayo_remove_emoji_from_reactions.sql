-- F04.1 みたよ！ボタン移行: timeline_post_reactions から emoji 列を削除し UNIQUE キーを変更する
-- 既存の絵文字リアクションデータは移行前に一度クリアする（みたよ！に概念が変わるため）
DELETE FROM timeline_post_reactions;

-- 新 UNIQUE キー (timeline_post_id, user_id) を先に作る
-- これにより FK fk_post_reactions_post が参照する timeline_post_id のインデックスが確保され、
-- 旧 UNIQUE キーを安全に削除できる。データを先ほど空にしたので重複は発生しない。
ALTER TABLE timeline_post_reactions
    ADD CONSTRAINT uq_tpr_post_user UNIQUE (timeline_post_id, user_id);

-- 旧 UNIQUE キーを削除（環境により名前が異なる：uq_tpr_post_user_emoji / uk_post_reactions）
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timeline_post_reactions' AND INDEX_NAME = 'uq_tpr_post_user_emoji');
SET @sql := IF(@idx_exists > 0, 'ALTER TABLE timeline_post_reactions DROP INDEX uq_tpr_post_user_emoji', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'timeline_post_reactions' AND INDEX_NAME = 'uk_post_reactions');
SET @sql := IF(@idx_exists > 0, 'ALTER TABLE timeline_post_reactions DROP INDEX uk_post_reactions', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- emoji 列を削除
ALTER TABLE timeline_post_reactions
    DROP COLUMN emoji;
