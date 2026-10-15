-- F05.3 電子印鑑: seal_stamp_logs テーブル
CREATE TABLE seal_stamp_logs (
    id                   BIGINT UNSIGNED       NOT NULL AUTO_INCREMENT,
    user_id              BIGINT UNSIGNED NOT NULL,
    seal_id              BIGINT UNSIGNED       NOT NULL,
    seal_hash_at_stamp   CHAR(64)     NOT NULL,
    target_type          VARCHAR(30)  NOT NULL,
    target_id            BIGINT UNSIGNED       NOT NULL,
    stamp_document_hash  CHAR(64)     NULL,
    is_revoked           BOOLEAN      NOT NULL DEFAULT FALSE,
    revoked_at           DATETIME     NULL,
    stamped_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_seal_stamp_logs_user_stamped (user_id, stamped_at DESC),
    INDEX idx_seal_stamp_logs_target (target_type, target_id),
    INDEX idx_seal_stamp_logs_seal (seal_id),
    CONSTRAINT fk_seal_stamp_logs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_seal_stamp_logs_seal FOREIGN KEY (seal_id) REFERENCES electronic_seals(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
