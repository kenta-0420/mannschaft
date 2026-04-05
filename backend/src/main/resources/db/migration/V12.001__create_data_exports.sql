CREATE TABLE data_exports (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id            BIGINT UNSIGNED NOT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    categories         VARCHAR(500),
    progress_percent   TINYINT UNSIGNED NOT NULL DEFAULT 0,
    current_step       VARCHAR(50),
    s3_key             VARCHAR(500),
    file_size_bytes    BIGINT UNSIGNED,
    zip_password_hash  VARCHAR(200),
    expires_at         DATETIME,
    error_message      VARCHAR(500),
    created_at         DATETIME     NOT NULL,
    completed_at       DATETIME,

    CONSTRAINT fk_data_exports_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT data_exports_status_check
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_data_exports_user_id ON data_exports(user_id);
CREATE INDEX idx_data_exports_status ON data_exports(status, created_at);
