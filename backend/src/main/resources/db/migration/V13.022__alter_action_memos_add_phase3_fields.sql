-- F02.5 Phase 3: action_memos テーブルへの追加フィールド
-- category: WORK/PRIVATE/OTHER カテゴリ
-- duration_minutes: 実績時間（分）
-- progress_rate: 進捗率（0.00〜100.00）
-- completes_todo: TODO完了フラグ
-- posted_team_id: チームタイムライン投稿済みチームID

ALTER TABLE action_memos
  ADD COLUMN category VARCHAR(16) NOT NULL DEFAULT 'PRIVATE'
    COMMENT 'メモのカテゴリ。WORK = 仕事 / PRIVATE = 私事 / OTHER = その他' AFTER content,
  ADD COLUMN duration_minutes INT UNSIGNED NULL DEFAULT NULL
    COMMENT '実績時間（分）。NULL = 未入力' AFTER category,
  ADD COLUMN progress_rate DECIMAL(5,2) NULL DEFAULT NULL
    COMMENT '記録時点の進捗率（0.00〜100.00）。NULL = 未入力' AFTER duration_minutes,
  ADD COLUMN completes_todo BOOLEAN NOT NULL DEFAULT FALSE
    COMMENT 'related_todo_id の TODO を完了扱いにするフラグ' AFTER related_todo_id,
  ADD COLUMN posted_team_id BIGINT UNSIGNED NULL DEFAULT NULL
    COMMENT 'チームタイムラインに投稿済みの場合のチームID' AFTER timeline_post_id;

ALTER TABLE action_memos
  ADD CONSTRAINT fk_am_posted_team
    FOREIGN KEY (posted_team_id) REFERENCES teams(id)
    ON DELETE SET NULL,
  ADD INDEX idx_am_category_date (user_id, category, memo_date, deleted_at),
  ADD INDEX idx_am_posted_team (posted_team_id, memo_date);
