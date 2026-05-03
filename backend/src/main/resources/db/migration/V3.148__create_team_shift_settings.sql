-- F03.5 Phase 5: チーム単位のシフトリマインド間隔カスタマイズ設定テーブル
CREATE TABLE team_shift_settings (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id BIGINT UNSIGNED NOT NULL,
    reminder_48h_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    reminder_24h_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    reminder_12h_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_team_shift_settings_team_id (team_id),
    CONSTRAINT fk_team_shift_settings_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
