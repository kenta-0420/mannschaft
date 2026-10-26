-- インシデントカテゴリテーブル
CREATE TABLE incident_categories (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type  VARCHAR(50)  NOT NULL,
    scope_id    BIGINT UNSIGNED NOT NULL,
    name        VARCHAR(100) NOT NULL,
    sla_hours   INT          NOT NULL DEFAULT 72 COMMENT 'デフォルトSLA時間',
    is_active   TINYINT(1)   NOT NULL DEFAULT 1,
    created_by  BIGINT UNSIGNED NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,
    deleted_at  DATETIME     NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ic_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    INDEX idx_ic_scope (scope_type, scope_id, is_active),
    UNIQUE KEY uq_ic_scope_name (scope_type, scope_id, name, deleted_at)
);
