CREATE TABLE safety_check_message_presets (
    id          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    body        VARCHAR(200)     NOT NULL,
    sort_order  INT              NOT NULL DEFAULT 0,
    is_active   BOOLEAN          NOT NULL DEFAULT TRUE,
    created_at  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='安否確認メッセージプリセット';

-- Seed: デフォルトプリセット5件
INSERT INTO safety_check_message_presets (body, sort_order) VALUES
    ('無事です', 1),
    ('軽傷ですが対応可能です', 2),
    ('支援が必要です', 3),
    ('避難中です', 4),
    ('連絡が取れない状況です', 5);
