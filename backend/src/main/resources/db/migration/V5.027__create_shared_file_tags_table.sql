CREATE TABLE shared_file_tags (
    id         BIGINT UNSIGNED      NOT NULL AUTO_INCREMENT,
    file_id    BIGINT UNSIGNED      NOT NULL,
    tag_name   VARCHAR(50) NOT NULL,
    user_id    BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_tags_file_tag_user (file_id, tag_name, user_id),
    CONSTRAINT fk_file_tags_file FOREIGN KEY (file_id) REFERENCES shared_files(id) ON DELETE CASCADE,
    CONSTRAINT fk_file_tags_user FOREIGN KEY (user_id) REFERENCES users(id)         ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
