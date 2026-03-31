-- APIキーテーブル
CREATE TABLE api_keys (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type          VARCHAR(50)  NOT NULL,
    scope_id            BIGINT UNSIGNED NOT NULL,
    name                VARCHAR(100) NOT NULL,
    key_prefix          VARCHAR(8)   NOT NULL COMMENT '先頭8文字（プレフィックス検索用）',
    key_hash            VARCHAR(60)  NOT NULL COMMENT 'bcryptハッシュ',
    scope_permission    VARCHAR(20)  NOT NULL DEFAULT 'READ_WRITE' COMMENT 'READ_ONLY/READ_WRITE',
    rate_limit_per_hour INT          NOT NULL DEFAULT 1000,
    expires_at          DATETIME     NULL COMMENT 'NULL=無期限',
    last_used_at        DATETIME     NULL,
    is_active           TINYINT(1)   NOT NULL DEFAULT 1,
    created_by          BIGINT UNSIGNED NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME     NOT NULL,
    updated_at          DATETIME     NOT NULL,
    deleted_at          DATETIME     NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ak_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    INDEX idx_ak_scope (scope_type, scope_id),
    INDEX idx_ak_prefix (key_prefix, is_active)
);
