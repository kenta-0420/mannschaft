-- F05.2 回覧板: circulation_recipients テーブル
CREATE TABLE circulation_recipients (
    id            BIGINT UNSIGNED      NOT NULL AUTO_INCREMENT,
    document_id   BIGINT UNSIGNED      NOT NULL,
    user_id       BIGINT UNSIGNED NOT NULL,
    sort_order    INT         NOT NULL DEFAULT 0,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    stamped_at    DATETIME    NULL,
    seal_id       BIGINT UNSIGNED      NULL,
    seal_variant  VARCHAR(20) NULL,
    tilt_angle    SMALLINT    NOT NULL DEFAULT 0,
    is_flipped    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_circulation_recipients_doc_user (document_id, user_id),
    CONSTRAINT fk_circulation_recipients_document FOREIGN KEY (document_id) REFERENCES circulation_documents(id) ON DELETE CASCADE,
    CONSTRAINT fk_circulation_recipients_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
