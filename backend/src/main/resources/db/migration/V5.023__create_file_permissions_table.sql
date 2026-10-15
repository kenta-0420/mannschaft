CREATE TABLE file_permissions (
    id                     BIGINT UNSIGNED      NOT NULL AUTO_INCREMENT,
    target_type            VARCHAR(10) NOT NULL,
    target_id              BIGINT UNSIGNED      NOT NULL,
    permission_type        VARCHAR(10) NOT NULL,
    permission_target_type VARCHAR(10) NOT NULL,
    permission_target_id   BIGINT UNSIGNED      NOT NULL,
    created_at             DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
