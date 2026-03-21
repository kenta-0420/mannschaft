-- F03.8 イベント管理: ゲスト招待トークンテーブル
CREATE TABLE event_guest_invite_tokens (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    event_id            BIGINT          NOT NULL,
    token               CHAR(36)        NOT NULL,
    label               VARCHAR(100),
    max_uses            INT UNSIGNED,
    used_count          INT UNSIGNED    NOT NULL DEFAULT 0,
    expires_at          DATETIME,
    is_active           BOOLEAN         NOT NULL DEFAULT TRUE,
    created_by          BIGINT,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_egit_token (token),
    CONSTRAINT fk_event_guest_invite_tokens_event
        FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE,
    CONSTRAINT fk_event_guest_invite_tokens_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    INDEX idx_egit_event_active (event_id, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='イベントゲスト招待トークン';
