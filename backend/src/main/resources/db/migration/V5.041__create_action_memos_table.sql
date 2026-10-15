-- F02.5 行動メモ本体テーブル
-- ADHD 傾向のあるユーザー向けの「行動ログ」を保持する。
-- 必須項目は content のみ。memo_date は JST 基準で自動セット（アプリ層）。
CREATE TABLE action_memos (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id          BIGINT UNSIGNED NOT NULL COMMENT '所有ユーザー',
    memo_date        DATE            NOT NULL COMMENT 'このメモが属する日付（JST 基準）',
    content          TEXT            NOT NULL COMMENT 'メモ本文（最大5,000文字。アプリ層で検証）',
    mood             VARCHAR(20)     NULL COMMENT 'GREAT / GOOD / OK / TIRED / BAD。設定 OFF のユーザーは常に NULL',
    related_todo_id  BIGINT UNSIGNED NULL COMMENT 'FK → todos。PERSONAL スコープのみ許可（アプリ層で検証）',
    timeline_post_id BIGINT UNSIGNED NULL COMMENT 'FK → timeline_posts。publish-daily 成功時のみ埋まる',
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at       DATETIME        NULL COMMENT '論理削除日時',
    PRIMARY KEY (id),
    CONSTRAINT fk_action_memos_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_action_memos_related_todo
        FOREIGN KEY (related_todo_id) REFERENCES todos (id) ON DELETE SET NULL,
    CONSTRAINT fk_action_memos_timeline_post
        FOREIGN KEY (timeline_post_id) REFERENCES timeline_posts (id) ON DELETE SET NULL,
    INDEX idx_am_user_date    (user_id, memo_date, deleted_at, created_at),
    INDEX idx_am_user_created (user_id, created_at, deleted_at),
    INDEX idx_am_user_mood    (user_id, memo_date, mood, deleted_at),
    INDEX idx_am_related_todo (related_todo_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
