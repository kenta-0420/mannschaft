-- F03.4 予約管理: 営業時間テーブル
CREATE TABLE reservation_business_hours (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    team_id     BIGINT      NOT NULL,
    day_of_week VARCHAR(3)  NOT NULL,
    is_open     BOOLEAN     NOT NULL DEFAULT TRUE,
    open_time   TIME,
    close_time  TIME,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_reservation_bh_team
        FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    UNIQUE KEY uk_reservation_bh_team_day (team_id, day_of_week)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
