-- F09.13 物件履歴台帳: 業者マスタテーブル
-- 設計書 §3 vendors テーブル定義に対応
CREATE TABLE vendors (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type       VARCHAR(20)     NOT NULL,
    scope_id         BIGINT UNSIGNED NOT NULL,
    name             VARCHAR(150)    NOT NULL,
    name_kana        VARCHAR(200)    NULL,
    category         VARCHAR(30)     NULL,
    phone            VARCHAR(30)     NULL,
    email            VARCHAR(255)    NULL,
    website          VARCHAR(500)    NULL,
    postal_code      VARCHAR(10)     NULL,
    address          VARCHAR(255)    NULL,
    representative   VARCHAR(100)    NULL,
    contact_person   VARCHAR(100)    NULL,
    license_number   VARCHAR(100)    NULL,
    license_expiry   DATE            NULL,
    note             TEXT            NULL,
    is_active        TINYINT(1)      NOT NULL DEFAULT 1,
    created_by       BIGINT UNSIGNED NOT NULL,
    version          BIGINT          NOT NULL DEFAULT 0,
    created_at       DATETIME        NOT NULL,
    updated_at       DATETIME        NOT NULL,
    deleted_at       DATETIME        NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_vendors_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_vendors_scope_type CHECK (scope_type IN ('TEAM','ORGANIZATION')),
    CONSTRAINT chk_vendors_category CHECK (
        category IS NULL OR category IN ('CONSTRUCTION','INSPECTION','CONSULTING','CLEANING','SECURITY','OTHER')
    ),
    INDEX idx_vendors_scope (scope_type, scope_id, is_active, name_kana),
    UNIQUE KEY uq_vendors_scope_name (scope_type, scope_id, name, deleted_at),
    INDEX idx_vendors_category (scope_type, scope_id, category, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
