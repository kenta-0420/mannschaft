-- F05.1 掲示板: カテゴリテーブル
CREATE TABLE bulletin_categories (
    id         BIGINT UNSIGNED       NOT NULL AUTO_INCREMENT,
    scope_type VARCHAR(20)  NOT NULL,
    scope_id   BIGINT UNSIGNED       NOT NULL,
    name       VARCHAR(50)  NOT NULL,
    description VARCHAR(200),
    display_order INT       NOT NULL DEFAULT 0,
    color      VARCHAR(7),
    post_min_role VARCHAR(20) NOT NULL DEFAULT 'MEMBER_PLUS',
    created_by BIGINT UNSIGNED,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    deleted_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_bulletin_categories_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
