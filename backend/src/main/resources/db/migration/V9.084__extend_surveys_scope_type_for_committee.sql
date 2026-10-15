-- F04.10: surveys.scope_type に COMMITTEE 値を追加（VARCHAR なのでコメント更新のみ）
ALTER TABLE surveys
    MODIFY COLUMN scope_type VARCHAR(20) NOT NULL
        COMMENT 'スコープ種別: TEAM / ORGANIZATION / COMMITTEE';
