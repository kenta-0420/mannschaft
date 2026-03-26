-- F03.4 予約管理: ブロック時間テーブル
CREATE TABLE reservation_blocked_times (
    id           BIGINT UNSIGNED       NOT NULL AUTO_INCREMENT,
    team_id      BIGINT UNSIGNED NOT NULL,
    blocked_date DATE         NOT NULL,
    start_time   TIME,
    end_time     TIME,
    reason       VARCHAR(200),
    created_by   BIGINT UNSIGNED,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_reservation_bt_team
        FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_reservation_bt_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
