-- チームマスターテーブル
CREATE TABLE teams (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    name_kana VARCHAR(100) NULL,
    nickname1 VARCHAR(50) NULL,
    nickname2 VARCHAR(50) NULL,
    template VARCHAR(30) NULL,
    prefecture VARCHAR(20) NULL,
    city VARCHAR(50) NULL,
    visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    supporter_enabled TINYINT(1) NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    archived_at DATETIME NULL,
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_teams_visibility CHECK (visibility IN ('PUBLIC','ORGANIZATION_ONLY','PRIVATE'))
);
CREATE INDEX idx_teams_visibility ON teams (visibility);
