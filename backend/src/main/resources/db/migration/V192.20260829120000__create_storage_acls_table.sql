-- Presigned upload のサーバー採番キーを所有者・スコープ・期限付きで管理する共通 ACL 台帳
CREATE TABLE storage_acls (
    id BINARY(16) NOT NULL,
    file_key VARCHAR(500) NOT NULL,
    owner_id BIGINT NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_id BIGINT NOT NULL,
    acl_mode VARCHAR(24) NOT NULL DEFAULT 'CONTENT_BOUND',
    content_type VARCHAR(100) NOT NULL,
    reference_type VARCHAR(64) NULL,
    reference_id BIGINT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_storage_acls_file_key (file_key),
    KEY idx_storage_acls_owner (owner_id),
    KEY idx_storage_acls_scope (scope_type, scope_id),
    KEY idx_storage_acls_expires (expires_at)
);
