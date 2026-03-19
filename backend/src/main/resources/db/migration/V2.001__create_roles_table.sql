-- ロール定義テーブル（固定6種）
CREATE TABLE roles (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(30) NOT NULL,
    display_name VARCHAR(50) NOT NULL,
    priority INT NOT NULL,
    is_system TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_roles_name UNIQUE (name)
);
