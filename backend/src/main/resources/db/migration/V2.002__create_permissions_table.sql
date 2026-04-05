-- パーミッション定義テーブル
CREATE TABLE permissions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    scope VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_permissions_name UNIQUE (name),
    CONSTRAINT chk_permissions_scope CHECK (scope IN ('PLATFORM','ORGANIZATION','TEAM'))
);
