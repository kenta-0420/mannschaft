-- F20.1: ナビゲーションバー 個人並び替え（D&D）順序の永続化
-- user_nav_settings に個人別ナビ表示順カラムを追加する。
-- NULL の場合は nav_features.sort_order 昇順（マスタ既定順）で表示する。

ALTER TABLE user_nav_settings
    ADD COLUMN nav_display_order JSON NULL
        COMMENT 'ユーザー個別ナビ表示順。nav_features.keyの配列。NULL=マスタsort_order順'
        AFTER hidden_nav_keys;
