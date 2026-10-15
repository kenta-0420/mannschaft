-- チーム有効モジュールテーブル
CREATE TABLE team_enabled_modules (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id BIGINT UNSIGNED NOT NULL,
    module_id BIGINT UNSIGNED NOT NULL,
    is_enabled TINYINT(1) NOT NULL DEFAULT 1,
    enabled_at DATETIME NULL,
    disabled_at DATETIME NULL,
    enabled_by BIGINT UNSIGNED NULL,
    trial_expires_at DATETIME NULL,
    trial_used TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_team_module UNIQUE (team_id, module_id),
    CONSTRAINT fk_team_enabled_modules_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_team_enabled_modules_module FOREIGN KEY (module_id) REFERENCES module_definitions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_team_enabled_modules_user FOREIGN KEY (enabled_by) REFERENCES users(id) ON DELETE SET NULL
);
