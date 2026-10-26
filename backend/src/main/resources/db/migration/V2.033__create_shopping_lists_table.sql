-- F01.4: お買い物リスト（ヘッダー）
CREATE TABLE shopping_lists (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id     BIGINT UNSIGNED NOT NULL,
    name        VARCHAR(100)    NOT NULL COMMENT 'リスト名',
    is_template BOOLEAN         NOT NULL DEFAULT FALSE COMMENT 'テンプレートリスト',
    status      VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED',
    created_by  BIGINT UNSIGNED NOT NULL,
    deleted_at  DATETIME        NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_sl_team (team_id, deleted_at, created_at DESC),
    CONSTRAINT fk_sl_team FOREIGN KEY (team_id)    REFERENCES teams (id),
    CONSTRAINT fk_sl_user FOREIGN KEY (created_by)  REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
