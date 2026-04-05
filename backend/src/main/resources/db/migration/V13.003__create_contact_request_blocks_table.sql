-- F04.8: 連絡先申請事前拒否テーブル
CREATE TABLE contact_request_blocks (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '拒否設定をしたユーザー',
    blocked_id  BIGINT UNSIGNED NOT NULL COMMENT '申請を拒否するユーザー',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_crb (user_id, blocked_id),
    INDEX idx_crb_blocked (blocked_id, user_id),
    CONSTRAINT fk_crb_user    FOREIGN KEY (user_id)    REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_crb_blocked FOREIGN KEY (blocked_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='連絡先申請ブロック（事前拒否）';
