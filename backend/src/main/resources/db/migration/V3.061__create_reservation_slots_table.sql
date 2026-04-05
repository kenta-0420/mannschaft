-- F03.4 予約管理: 予約スロットテーブル
CREATE TABLE reservation_slots (
    id              BIGINT UNSIGNED        NOT NULL AUTO_INCREMENT,
    team_id         BIGINT UNSIGNED NOT NULL,
    staff_user_id   BIGINT UNSIGNED,
    title           VARCHAR(200),
    slot_date       DATE          NOT NULL,
    start_time      TIME          NOT NULL,
    end_time        TIME          NOT NULL,
    booked_count    INT           NOT NULL DEFAULT 0,
    slot_status     VARCHAR(20)   NOT NULL DEFAULT 'AVAILABLE',
    recurrence_rule JSON,
    parent_slot_id  BIGINT UNSIGNED,
    is_exception    BOOLEAN       NOT NULL DEFAULT FALSE,
    price           DECIMAL(10,2),
    closed_reason   VARCHAR(20),
    note            TEXT,
    created_by      BIGINT UNSIGNED,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT fk_reservation_slots_team
        FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_reservation_slots_staff
        FOREIGN KEY (staff_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_reservation_slots_parent
        FOREIGN KEY (parent_slot_id) REFERENCES reservation_slots (id) ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_slots_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    INDEX idx_reservation_slots_team_date (team_id, slot_date),
    INDEX idx_reservation_slots_staff_date (staff_user_id, slot_date),
    INDEX idx_reservation_slots_team_status_date (team_id, slot_status, slot_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
