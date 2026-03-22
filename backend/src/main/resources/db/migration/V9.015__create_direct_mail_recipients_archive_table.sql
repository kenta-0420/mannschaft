-- F09.6: ダイレクトメール受信者アーカイブ
CREATE TABLE direct_mail_recipients_archive (
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
    archived_at     DATETIME        NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
