-- F04.3 プッシュ通知: プッシュ購読テーブル
CREATE TABLE push_subscriptions (
    id                  BIGINT UNSIGNED          NOT NULL AUTO_INCREMENT,
    user_id             BIGINT UNSIGNED NOT NULL,
    endpoint            VARCHAR(2000)   NOT NULL,
    p256dh_key          VARCHAR(500)    NOT NULL,
    auth_key            VARCHAR(500)    NOT NULL,
    user_agent          VARCHAR(500),
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at        DATETIME,

    PRIMARY KEY (id),
    CONSTRAINT fk_push_subscriptions_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    UNIQUE KEY uq_push_subscriptions_endpoint (endpoint(500))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='プッシュ購読';
