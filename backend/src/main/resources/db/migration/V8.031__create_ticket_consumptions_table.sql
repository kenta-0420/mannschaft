-- F08.5: チケット消化履歴テーブル
CREATE TABLE ticket_consumptions (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    book_id             BIGINT UNSIGNED NOT NULL,
    consumed_by         BIGINT UNSIGNED NOT NULL,
    reservation_id      BIGINT UNSIGNED NULL,
    service_record_id   BIGINT UNSIGNED NULL,
    consumed_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    note                VARCHAR(500)    NULL,
    is_voided           BOOLEAN         NOT NULL DEFAULT FALSE,
    voided_at           DATETIME        NULL,
    voided_by           BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    INDEX idx_tc_book (book_id, is_voided, consumed_at),
    CONSTRAINT fk_tc_book FOREIGN KEY (book_id) REFERENCES ticket_books(id),
    CONSTRAINT fk_tc_consumed_by FOREIGN KEY (consumed_by) REFERENCES users(id),
    CONSTRAINT fk_tc_voided_by FOREIGN KEY (voided_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
