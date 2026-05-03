-- F03.15 個人時間割: ユーザー定義カスタムメモ項目（最大10件/ユーザー）
CREATE TABLE timetable_slot_user_note_fields (
    id            BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT,
    user_id       BIGINT UNSIGNED   NOT NULL                COMMENT 'FK → users.id',
    label         VARCHAR(50)       NOT NULL                COMMENT '項目名（例: "演習問題"）',
    placeholder   VARCHAR(100)      NULL                    COMMENT '入力欄プレースホルダ',
    sort_order    TINYINT UNSIGNED  NOT NULL DEFAULT 0      COMMENT '表示順',
    max_length    SMALLINT UNSIGNED NOT NULL DEFAULT 2000   COMMENT '文字数上限（500/2000/5000）',
    created_at    DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_tsunf_user_sort (user_id, sort_order),
    UNIQUE KEY uq_tsunf_user_label (user_id, label),

    CONSTRAINT fk_tsunf_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_tsunf_max_length CHECK (max_length IN (500, 2000, 5000))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='F03.15 ユーザー定義カスタムメモ項目';
