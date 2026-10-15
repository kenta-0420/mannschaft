-- F01.4: 当番ローテーション定義
CREATE TABLE duty_rotations (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id        BIGINT UNSIGNED NOT NULL,
    duty_name      VARCHAR(100)    NOT NULL COMMENT '当番名',
    rotation_type  VARCHAR(20)     NOT NULL DEFAULT 'DAILY' COMMENT 'DAILY / WEEKLY',
    member_order   JSON            NOT NULL COMMENT 'ローテーション順のメンバーID配列',
    start_date     DATE            NOT NULL COMMENT 'ローテーション開始日',
    icon           VARCHAR(10)     NULL     COMMENT '表示アイコン（絵文字）',
    is_enabled     BOOLEAN         NOT NULL DEFAULT TRUE,
    created_by     BIGINT UNSIGNED NOT NULL,
    deleted_at     DATETIME        NULL,
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_dr_team (team_id, deleted_at, is_enabled),
    CONSTRAINT fk_dr_team FOREIGN KEY (team_id)    REFERENCES teams (id),
    CONSTRAINT fk_dr_user FOREIGN KEY (created_by)  REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
