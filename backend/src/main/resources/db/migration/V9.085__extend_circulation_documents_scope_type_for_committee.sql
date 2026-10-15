-- F04.10: circulation_documents.scope_type に COMMITTEE 値を追加（VARCHAR なのでコメント更新のみ）
ALTER TABLE circulation_documents
    MODIFY COLUMN scope_type VARCHAR(20) NOT NULL
        COMMENT 'スコープ種別: TEAM / ORGANIZATION / COMMITTEE';
