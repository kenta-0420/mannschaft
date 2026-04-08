-- F04.x: メンション通知テーブル
-- タイムライン投稿・チャットメッセージ等で `@displayName` 表記により
-- ユーザーに対するメンションが発生した際に1行ずつ挿入される。
CREATE TABLE mentions (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id                BIGINT UNSIGNED NOT NULL COMMENT 'メンションされた人',
    mentioned_by_user_id   BIGINT UNSIGNED NOT NULL COMMENT 'メンションした人',
    content_type           VARCHAR(20)     NOT NULL COMMENT 'POST | MESSAGE | THREAD | COMMENT',
    content_id             BIGINT UNSIGNED NOT NULL COMMENT '元コンテンツのID',
    content_title          VARCHAR(200)    NULL     COMMENT 'スレッドタイトル等（あれば）',
    content_snippet        VARCHAR(500)    NOT NULL COMMENT '本文の抜粋（最大500文字）',
    url                    VARCHAR(500)    NOT NULL COMMENT '該当コンテンツへの遷移URL',
    is_read                BOOLEAN         NOT NULL DEFAULT FALSE,
    read_at                DATETIME        NULL,
    created_at             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_mentions_user_unread (user_id, is_read, created_at DESC),
    INDEX idx_mentions_user_recent (user_id, created_at DESC),
    INDEX idx_mentions_content (content_type, content_id),
    CONSTRAINT fk_mentions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_mentions_by_user FOREIGN KEY (mentioned_by_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='メンション通知';
