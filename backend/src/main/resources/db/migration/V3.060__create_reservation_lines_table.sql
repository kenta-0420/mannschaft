-- F03.4 予約管理: 予約ライン（メニュー）テーブル
CREATE TABLE reservation_lines (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    team_id         BIGINT       NOT NULL,
    name            VARCHAR(50)  NOT NULL,
    description     VARCHAR(200),
    display_order   INT          NOT NULL DEFAULT 1,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    default_staff_user_id BIGINT,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT fk_reservation_lines_team
        FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_reservation_lines_default_staff
        FOREIGN KEY (default_staff_user_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
