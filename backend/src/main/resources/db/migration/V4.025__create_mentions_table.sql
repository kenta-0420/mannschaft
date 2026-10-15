-- メンションテーブル
-- ユーザーへのメンション（@ユーザー名）を管理する。
-- ポリモーフィック設計: target_type + target_id で対象レコード（タイムライン投稿、チャットメッセージ等）を参照する。
CREATE TABLE mentions (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    mentioned_user_id BIGINT UNSIGNED NOT NULL COMMENT 'メンションされたユーザー',
    mentioned_by_id   BIGINT UNSIGNED NOT NULL COMMENT 'メンションしたユーザー',
    target_type       VARCHAR(30)     NOT NULL COMMENT '対象種別 (TIMELINE_POST, CHAT_MESSAGE 等)',
    target_id         BIGINT UNSIGNED NOT NULL COMMENT '対象レコードの ID',
    content_snippet   VARCHAR(200)             COMMENT '本文の抜粋',
    is_read           BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at           DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT fk_mention_user FOREIGN KEY (mentioned_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_mention_by   FOREIGN KEY (mentioned_by_id)   REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_mention_user_read (mentioned_user_id, is_read, created_at DESC),
    INDEX idx_mention_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
