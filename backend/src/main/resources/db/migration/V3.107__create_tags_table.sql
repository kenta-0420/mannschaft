-- 汎用タグマスタ（ポイっとメモ / TODO 共通。PERSONAL / TEAM / ORGANIZATION スコープ）
CREATE TABLE tags (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    scope_type        VARCHAR(20)  NOT NULL COMMENT 'PERSONAL / TEAM / ORGANIZATION',
    scope_id          BIGINT       NOT NULL COMMENT 'PERSONAL=user_id / TEAM=team_id / ORGANIZATION=organization_id',
    name              VARCHAR(30)  NOT NULL,
    color             VARCHAR(7)   NULL     COMMENT 'HEX カラーコード (#RRGGBB)',
    usage_count       INT          NOT NULL DEFAULT 0,
    created_by        BIGINT UNSIGNED NOT NULL COMMENT 'FK -> users.id (ON DELETE RESTRICT)',
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tags_scope_name (scope_type, scope_id, name),
    CONSTRAINT fk_tags_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='汎用タグマスタ';

CREATE INDEX idx_tags_scope ON tags (scope_type, scope_id);
CREATE INDEX idx_tags_created_by ON tags (created_by);
