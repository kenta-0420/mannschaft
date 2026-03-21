-- F04.3 プッシュ通知: 通知テーブル
CREATE TABLE notifications (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    user_id             BIGINT          NOT NULL,
    notification_type   VARCHAR(50)     NOT NULL,
    priority            VARCHAR(10)     NOT NULL DEFAULT 'NORMAL',
    title               VARCHAR(200)    NOT NULL,
    body                VARCHAR(1000),
    source_type         VARCHAR(50)     NOT NULL,
    source_id           BIGINT,
    scope_type          VARCHAR(20)     NOT NULL,
    scope_id            BIGINT,
    action_url          VARCHAR(500),
    actor_id            BIGINT,
    is_read             BOOLEAN         NOT NULL DEFAULT FALSE,
    read_at             DATETIME,
    channels_sent       JSON,
    snoozed_until       DATETIME,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT fk_notifications_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_actor
        FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE SET NULL,
    INDEX idx_notifications_user_read_created (user_id, is_read, created_at DESC),
    INDEX idx_notifications_user_created (user_id, created_at DESC),
    INDEX idx_notifications_source (source_type, source_id),
    INDEX idx_notifications_scope (scope_type, scope_id, created_at DESC),
    INDEX idx_notifications_snoozed (snoozed_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知';
