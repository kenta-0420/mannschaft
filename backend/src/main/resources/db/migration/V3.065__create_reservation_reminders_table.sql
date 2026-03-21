-- F03.4 予約管理: リマインダーテーブル
CREATE TABLE reservation_reminders (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    reservation_id BIGINT      NOT NULL,
    remind_at      DATETIME    NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    sent_at        DATETIME,
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_reservation_reminders_reservation
        FOREIGN KEY (reservation_id) REFERENCES reservations (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
