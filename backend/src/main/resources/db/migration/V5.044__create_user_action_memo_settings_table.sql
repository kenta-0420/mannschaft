-- F02.5 ユーザー別 行動メモ設定テーブル
-- PK = user_id（1ユーザー1レコード）。
-- レコード未作成のユーザーはデフォルト値（mood_enabled = false）として扱う（Service 層）。
CREATE TABLE user_action_memo_settings (
    user_id      BIGINT UNSIGNED NOT NULL COMMENT 'PK 兼 FK → users',
    mood_enabled BOOLEAN         NOT NULL DEFAULT FALSE COMMENT '気分（mood）入力 UI を表示するか。デフォルト OFF（ドライモード）',
    created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_action_memo_settings_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
