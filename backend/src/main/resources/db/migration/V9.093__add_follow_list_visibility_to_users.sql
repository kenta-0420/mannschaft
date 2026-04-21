-- F04.4 / F01.7 Phase 2: フォロー一覧公開設定を users テーブルに追加
ALTER TABLE users
    ADD COLUMN follow_list_visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC';
