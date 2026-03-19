-- 組織マスターテーブル
CREATE TABLE organizations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    name_kana VARCHAR(100) NULL,
    nickname1 VARCHAR(50) NULL,
    nickname2 VARCHAR(50) NULL,
    org_type VARCHAR(30) NOT NULL,
    parent_organization_id BIGINT UNSIGNED NULL,
    prefecture VARCHAR(20) NULL,
    city VARCHAR(50) NULL,
    visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    hierarchy_visibility VARCHAR(20) NOT NULL DEFAULT 'NONE',
    supporter_enabled TINYINT(1) NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    archived_at DATETIME NULL,
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_organizations_parent FOREIGN KEY (parent_organization_id) REFERENCES organizations (id),
    CONSTRAINT chk_organizations_visibility CHECK (visibility IN ('PUBLIC','PRIVATE')),
    CONSTRAINT chk_organizations_hierarchy_visibility CHECK (hierarchy_visibility IN ('NONE','BASIC','FULL'))
);
CREATE INDEX idx_organizations_parent ON organizations (parent_organization_id);
CREATE INDEX idx_organizations_visibility ON organizations (visibility);
