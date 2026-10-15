-- F01.4: 記念日・誕生日リマインダー
CREATE TABLE team_anniversaries (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id             BIGINT UNSIGNED NOT NULL,
    name                VARCHAR(200)    NOT NULL COMMENT '記念日名',
    date                DATE            NOT NULL COMMENT '記念日の日付',
    repeat_annually     BOOLEAN         NOT NULL DEFAULT TRUE,
    notify_days_before  TINYINT         NOT NULL DEFAULT 1 COMMENT '何日前に事前通知するか',
    created_by          BIGINT UNSIGNED NOT NULL,
    deleted_at          DATETIME        NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_ta_team (team_id, deleted_at),
    INDEX idx_ta_date (date),
    CONSTRAINT fk_ta_team FOREIGN KEY (team_id)    REFERENCES teams (id),
    CONSTRAINT fk_ta_user FOREIGN KEY (created_by)  REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
