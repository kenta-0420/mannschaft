-- F09.6: ダイレクトメール画像アップロード
CREATE TABLE direct_mail_image_uploads (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    mail_log_id     BIGINT UNSIGNED NULL,
    s3_key          VARCHAR(500)    NOT NULL,
    file_name       VARCHAR(255)    NOT NULL,
    file_size       INTEGER         NOT NULL,
    content_type    VARCHAR(100)    NOT NULL,
    uploaded_by     BIGINT UNSIGNED NOT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_dmiu_log (mail_log_id),
    INDEX idx_dmiu_orphan (mail_log_id, created_at),
    CONSTRAINT fk_dmiu_log FOREIGN KEY (mail_log_id) REFERENCES direct_mail_logs (id) ON DELETE SET NULL,
    CONSTRAINT fk_dmiu_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
