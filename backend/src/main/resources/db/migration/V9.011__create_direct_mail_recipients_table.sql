-- F09.6: ダイレクトメール受信者
CREATE TABLE direct_mail_recipients (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    mail_log_id     BIGINT UNSIGNED NOT NULL,
    user_id         BIGINT UNSIGNED NOT NULL,
    email           VARCHAR(255)    NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    ses_message_id  VARCHAR(100)    NULL,
    opened_at       DATETIME        NULL,
    bounced_at      DATETIME        NULL,
    bounce_type     VARCHAR(20)     NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_dmr_log_status (mail_log_id, status),
    INDEX idx_dmr_user (user_id),
    INDEX idx_dmr_ses (ses_message_id),
    CONSTRAINT fk_dmr_log FOREIGN KEY (mail_log_id) REFERENCES direct_mail_logs (id) ON DELETE CASCADE,
    CONSTRAINT fk_dmr_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
