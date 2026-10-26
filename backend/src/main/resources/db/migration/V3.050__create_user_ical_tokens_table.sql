CREATE TABLE user_ical_tokens (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id        BIGINT UNSIGNED NOT NULL,
    token          VARCHAR(64)     NOT NULL,
    is_active      BOOLEAN         NOT NULL DEFAULT TRUE,
    last_polled_at DATETIME,
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_uit_user (user_id),
    UNIQUE KEY uq_uit_token (token),
    CONSTRAINT fk_uit_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='iCal購読URLトークン';
