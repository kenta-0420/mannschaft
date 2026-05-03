-- F03.15 個人時間割: 家族チーム共有先
CREATE TABLE personal_timetable_share_targets (
    id                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    personal_timetable_id    BIGINT UNSIGNED NOT NULL                COMMENT 'FK → personal_timetables.id',
    team_id                  BIGINT UNSIGNED NOT NULL                COMMENT 'FK → teams.id（FAMILY 限定。アプリ層で検証）',
    created_at               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_ptst_share (personal_timetable_id, team_id),
    INDEX idx_ptst_share_team (team_id),

    CONSTRAINT fk_ptst_personal_timetable FOREIGN KEY (personal_timetable_id) REFERENCES personal_timetables(id) ON DELETE CASCADE,
    CONSTRAINT fk_ptst_team               FOREIGN KEY (team_id)               REFERENCES teams(id)               ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='F03.15 個人時間割の家族チーム共有先（最大3）';
