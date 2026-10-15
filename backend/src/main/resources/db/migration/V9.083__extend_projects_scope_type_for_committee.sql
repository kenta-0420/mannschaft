-- F04.10: projects.scope_type に COMMITTEE 値を追加（VARCHAR なのでコメント更新のみ）
ALTER TABLE projects
    MODIFY COLUMN scope_type VARCHAR(20) NOT NULL
        COMMENT 'スコープ種別: PERSONAL / TEAM / ORGANIZATION / COMMITTEE';
