-- F03.13 学校日次・教科別出欠管理: クラス・学級担任マッピング
CREATE TABLE class_homerooms (
    id                         BIGINT UNSIGNED    NOT NULL AUTO_INCREMENT,
    team_id                    BIGINT UNSIGNED    NOT NULL COMMENT 'FK → teams.id（クラスチーム）',
    homeroom_teacher_user_id   BIGINT UNSIGNED    NOT NULL COMMENT 'FK → users.id（学級担任）',
    assistant_teacher_user_ids JSON               NULL     COMMENT '副担任配列 [123, 456]（最大3名）',
    academic_year              SMALLINT UNSIGNED  NOT NULL COMMENT '年度',
    effective_from             DATE               NOT NULL COMMENT '有効開始日',
    effective_until            DATE               NULL     COMMENT '有効終了日（NULL=現役）',
    created_by                 BIGINT UNSIGNED    NOT NULL COMMENT 'FK → users.id',
    created_at                 DATETIME           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 DATETIME           NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_ch_team_year (team_id, academic_year),
    INDEX idx_ch_homeroom_teacher (homeroom_teacher_user_id, effective_until),
    CONSTRAINT fk_ch_team          FOREIGN KEY (team_id)                  REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_ch_homeroom_user FOREIGN KEY (homeroom_teacher_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ch_created_by    FOREIGN KEY (created_by)               REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学級担任マッピング（クラスチームと担任の対応）';
