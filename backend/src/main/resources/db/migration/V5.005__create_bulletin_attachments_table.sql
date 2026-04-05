-- F05.1 掲示板: 添付ファイルテーブル
CREATE TABLE bulletin_attachments (
    id                BIGINT UNSIGNED        NOT NULL AUTO_INCREMENT,
    target_type       VARCHAR(10)   NOT NULL,
    target_id         BIGINT UNSIGNED        NOT NULL,
    file_key          VARCHAR(500)  NOT NULL,
    original_filename VARCHAR(255)  NOT NULL,
    file_size         BIGINT UNSIGNED        NOT NULL,
    content_type      VARCHAR(100)  NOT NULL,
    created_by        BIGINT UNSIGNED,
    created_at        DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_bulletin_attachments_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
