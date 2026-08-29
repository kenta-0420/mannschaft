CREATE TABLE multipart_abort_cleanups (
    id BINARY(16) NOT NULL,
    upload_id VARCHAR(255) NOT NULL,
    r2_key VARCHAR(500) NOT NULL,
    owner_id BIGINT NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    feature VARCHAR(30) NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    scope_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    next_attempt_at DATETIME NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_multipart_abort_cleanups_upload (upload_id),
    KEY idx_multipart_abort_cleanups_due (status, next_attempt_at)
);
