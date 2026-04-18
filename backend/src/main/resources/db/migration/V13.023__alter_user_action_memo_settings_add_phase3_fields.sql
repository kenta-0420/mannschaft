-- F02.5 Phase 3: user_action_memo_settings テーブルへの追加フィールド
-- default_post_team_id: デフォルト投稿先チームID
-- default_category: デフォルトカテゴリ

ALTER TABLE user_action_memo_settings
  ADD COLUMN default_post_team_id BIGINT UNSIGNED NULL DEFAULT NULL
    COMMENT '行動メモのデフォルト投稿先チーム' AFTER mood_enabled,
  ADD COLUMN default_category VARCHAR(16) NOT NULL DEFAULT 'PRIVATE'
    COMMENT 'メモ作成時のデフォルトカテゴリ' AFTER default_post_team_id;

ALTER TABLE user_action_memo_settings
  ADD CONSTRAINT fk_uams_default_team
    FOREIGN KEY (default_post_team_id) REFERENCES teams(id)
    ON DELETE SET NULL;
