-- F03.5 v2: メンバー単位の任意勤務制約テーブルを新設
-- 月次労働時間・月次勤務日数・連続勤務日数・月次夜勤数・シフト間休息時間を任意（全項目 NULL 可）で制御する
-- user_id NULL はチームデフォルト（全メンバー適用）。MySQL の NULL != NULL 特性により、
-- チームデフォルト（user_id IS NULL）の 1 チーム 1 件制御は Service 層で担保する（設計書 §3 備考）。
--
-- 全項目 NULL のレコードは意味が無いため DB 層の CHECK 制約でも拒否（Service 層と二重防御）。

CREATE TABLE member_work_constraints (
    id                              BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT,
    team_id                         BIGINT UNSIGNED   NOT NULL,
    user_id                         BIGINT UNSIGNED,
    max_monthly_hours               DECIMAL(6, 2),
    max_monthly_days                TINYINT UNSIGNED,
    max_consecutive_days            TINYINT UNSIGNED,
    max_night_shifts_per_month      TINYINT UNSIGNED,
    min_rest_hours_between_shifts   DECIMAL(4, 2),
    note                            VARCHAR(500),
    created_at                      DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_member_work_constraints_team_user (team_id, user_id),
    INDEX idx_member_work_constraints_team (team_id),

    CONSTRAINT fk_mwc_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_mwc_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,

    CONSTRAINT chk_mwc_not_all_null CHECK (
        max_monthly_hours IS NOT NULL
        OR max_monthly_days IS NOT NULL
        OR max_consecutive_days IS NOT NULL
        OR max_night_shifts_per_month IS NOT NULL
        OR min_rest_hours_between_shifts IS NOT NULL
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='F03.5 v2: メンバー単位の任意勤務制約';
