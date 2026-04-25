CREATE TABLE member_work_constraints (
  id                              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  team_id                         BIGINT UNSIGNED NOT NULL,
  user_id                         BIGINT UNSIGNED NULL COMMENT 'NULL=チームデフォルト制約',
  max_monthly_hours               DECIMAL(5,1) NULL COMMENT '月最大勤務時間',
  max_monthly_days                TINYINT UNSIGNED NULL COMMENT '月最大勤務日数',
  max_consecutive_days            TINYINT UNSIGNED NULL COMMENT '最大連続勤務日数',
  max_night_shifts_per_month      TINYINT UNSIGNED NULL COMMENT '月最大夜勤回数',
  min_rest_hours_between_shifts   DECIMAL(4,1) NULL COMMENT 'シフト間最低休憩時間（時間）',
  note                            VARCHAR(500) NULL,
  created_at                      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_work_constraints_team_user (team_id, user_id),
  INDEX idx_member_work_constraints_team_id (team_id),
  CONSTRAINT fk_member_work_constraints_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
  CONSTRAINT fk_member_work_constraints_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='メンバー勤務制約（任意設定）';
