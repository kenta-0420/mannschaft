-- F02.5 Phase 3: 既存の action_memos レコードに category = 'PRIVATE' を設定（冪等）
UPDATE action_memos SET category = 'PRIVATE' WHERE category IS NULL OR category = '';
