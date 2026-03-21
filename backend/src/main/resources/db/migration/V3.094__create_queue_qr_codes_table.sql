CREATE TABLE queue_qr_codes (
    id              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    category_id     BIGINT UNSIGNED,
    counter_id      BIGINT UNSIGNED,
    qr_token        VARCHAR(64)      NOT NULL,
    is_active       BOOLEAN          NOT NULL DEFAULT TRUE,
    created_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_qqr_token (qr_token),

    CONSTRAINT fk_qqr_category FOREIGN KEY (category_id) REFERENCES queue_categories (id) ON DELETE CASCADE,
    CONSTRAINT fk_qqr_counter FOREIGN KEY (counter_id) REFERENCES queue_counters (id) ON DELETE CASCADE,
    CONSTRAINT chk_qqr_xor CHECK (
        (category_id IS NOT NULL AND counter_id IS NULL) OR
        (category_id IS NULL AND counter_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='順番待ちQRコード';
