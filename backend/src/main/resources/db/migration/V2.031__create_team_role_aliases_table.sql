-- F01.4: チームごとのロール表示名カスタマイズ
CREATE TABLE team_role_aliases (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id       BIGINT UNSIGNED NOT NULL,
    role_name     VARCHAR(30)     NOT NULL COMMENT '対象ロール（ADMIN/DEPUTY_ADMIN/MEMBER/SUPPORTER）',
    display_alias VARCHAR(50)     NOT NULL COMMENT '表示名（例: ボス、監督）',
    updated_by    BIGINT UNSIGNED NOT NULL,
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_tra_team_role (team_id, role_name),
    CONSTRAINT fk_tra_team FOREIGN KEY (team_id)    REFERENCES teams (id),
    CONSTRAINT fk_tra_user FOREIGN KEY (updated_by)  REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
