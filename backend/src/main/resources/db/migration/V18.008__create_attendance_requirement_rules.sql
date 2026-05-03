CREATE TABLE attendance_requirement_rules (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NULL COMMENT '組織スコープ（team_id と排他）',
  team_id BIGINT NULL COMMENT 'チームスコープ（organization_id と排他）',
  term_id BIGINT NULL COMMENT 'NULLなら年度通算',
  academic_year SMALLINT NOT NULL COMMENT '学年度（例: 2025）',
  category VARCHAR(30) NOT NULL COMMENT 'GRADE_PROMOTION/GRADUATION/SUBJECT_CREDIT/PERFECT_ATTENDANCE/CUSTOM',
  name VARCHAR(100) NOT NULL COMMENT '規程名（例: 3年進級要件）',
  description TEXT NULL,
  min_attendance_rate DECIMAL(5,2) NULL COMMENT '最小出席率（%）',
  max_absence_days SMALLINT NULL COMMENT '最大欠席日数',
  max_absence_rate DECIMAL(5,2) NULL COMMENT '最大欠席率（%）',
  count_sick_bay_as_present BOOLEAN NOT NULL DEFAULT TRUE COMMENT '保健室登校を出席扱い',
  count_separate_room_as_present BOOLEAN NOT NULL DEFAULT TRUE COMMENT '別室登校を出席扱い',
  count_library_as_present BOOLEAN NOT NULL DEFAULT TRUE COMMENT '図書室登校を出席扱い',
  count_online_as_present BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'オンライン登校を出席扱い',
  count_home_learning_as_official_absence BOOLEAN NOT NULL DEFAULT FALSE COMMENT '家庭学習を公欠扱い',
  count_late_as_absence_threshold TINYINT NOT NULL DEFAULT 0 COMMENT '遅刻N回で欠席1日換算（0=換算なし）',
  warning_threshold_rate DECIMAL(5,2) NULL COMMENT '警告発火しきい値（%）',
  effective_from DATE NOT NULL,
  effective_until DATE NULL COMMENT 'NULLなら無期限',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_arr_org_year (organization_id, academic_year),
  INDEX idx_arr_team_year (team_id, academic_year),
  CONSTRAINT chk_arr_scope CHECK (
    (organization_id IS NOT NULL AND team_id IS NULL)
    OR (organization_id IS NULL AND team_id IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出席要件規程';
