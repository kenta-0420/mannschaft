CREATE TABLE shared_file_comments (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    file_id    BIGINT        NOT NULL,
    user_id    BIGINT        NULL,
    body       VARCHAR(2000) NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    updated_at DATETIME(6)   NOT NULL,
    deleted_at DATETIME(6)   NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_file_comments_file FOREIGN KEY (file_id) REFERENCES shared_files(id) ON DELETE CASCADE,
    CONSTRAINT fk_file_comments_user FOREIGN KEY (user_id) REFERENCES users(id)         ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
