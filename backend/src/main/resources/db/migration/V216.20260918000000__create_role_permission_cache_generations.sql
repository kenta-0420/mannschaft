-- CMP-260820-1012: role-permissions キャッシュの永続世代番号。
-- スコープ単位で世代を進め、旧世代のキーを論理的に到達不能にする。
CREATE TABLE role_permission_cache_generations (
    id BINARY(16) NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    generation BIGINT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_role_permission_cache_generations_scope UNIQUE (scope_type, scope_id),
    CONSTRAINT chk_role_permission_cache_generations_scope_type
        CHECK (scope_type IN ('TEAM', 'ORGANIZATION')),
    CONSTRAINT chk_role_permission_cache_generations_generation
        CHECK (generation >= 0)
);
