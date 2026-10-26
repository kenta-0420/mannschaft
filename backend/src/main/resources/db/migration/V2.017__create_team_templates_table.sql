-- チームテンプレートマスターテーブル
CREATE TABLE team_templates (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    slug VARCHAR(50) NOT NULL,
    description VARCHAR(500) NULL,
    icon_url VARCHAR(500) NULL,
    category VARCHAR(50) NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    created_by BIGINT UNSIGNED NULL,
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_team_templates_slug UNIQUE (slug),
    CONSTRAINT fk_team_templates_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
);
