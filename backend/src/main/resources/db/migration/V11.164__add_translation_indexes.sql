-- F11.2 不足インデックス追加
-- idx_ct_needs_update: 要更新翻訳のバッチ検索用
CREATE INDEX idx_ct_needs_update ON content_translations (status, source_type, scope_type, scope_id);

-- idx_ta_scope_lang: スコープ×言語別の有効翻訳者一覧取得用
CREATE INDEX idx_ta_scope_lang ON translation_assignments (scope_type, scope_id, language, is_active);
