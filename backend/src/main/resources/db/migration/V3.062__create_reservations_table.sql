-- F03.4 予約管理: 予約テーブル
CREATE TABLE reservations (
    id                  BIGINT UNSIGNED       NOT NULL AUTO_INCREMENT,
    reservation_slot_id BIGINT UNSIGNED       NOT NULL,
    line_id             BIGINT UNSIGNED       NOT NULL,
    team_id             BIGINT UNSIGNED NOT NULL,
    user_id             BIGINT UNSIGNED NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    booked_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at        DATETIME,
    cancelled_at        DATETIME,
    cancel_reason       VARCHAR(500),
    cancelled_by        VARCHAR(10),
    completed_at        DATETIME,
    user_note           VARCHAR(500),
    admin_note          VARCHAR(500),
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at          DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT fk_reservations_slot
        FOREIGN KEY (reservation_slot_id) REFERENCES reservation_slots (id) ON DELETE RESTRICT,
    CONSTRAINT fk_reservations_line
        FOREIGN KEY (line_id) REFERENCES reservation_lines (id) ON DELETE RESTRICT,
    CONSTRAINT fk_reservations_team
        FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_reservations_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    INDEX idx_reservations_user_status_booked (user_id, status, booked_at),
    INDEX idx_reservations_team_status_booked (team_id, status, booked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
