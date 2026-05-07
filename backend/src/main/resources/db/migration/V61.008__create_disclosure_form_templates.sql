-- F09.14 重要事項説明書: 様式テンプレートマスタ
-- 設計書 §3 disclosure_form_templates テーブル定義に対応
CREATE TABLE disclosure_form_templates (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code                 VARCHAR(50)     NOT NULL,
    name                 VARCHAR(150)    NOT NULL,
    prefecture_code      CHAR(2)         NULL,
    version              VARCHAR(20)     NOT NULL,
    is_standard          TINYINT(1)      NOT NULL DEFAULT 0,
    is_system_template   TINYINT(1)      NOT NULL DEFAULT 0,
    scope_type           VARCHAR(20)     NULL,
    scope_id             BIGINT UNSIGNED NULL,
    form_schema          JSON            NOT NULL,
    pdf_template_path    VARCHAR(500)    NULL,
    excel_template_key   VARCHAR(500)    NULL,
    effective_from       DATE            NULL,
    effective_until      DATE            NULL,
    is_active            TINYINT(1)      NOT NULL DEFAULT 1,
    created_by           BIGINT UNSIGNED NULL,
    version_lock         BIGINT          NOT NULL DEFAULT 0,
    created_at           DATETIME        NOT NULL,
    updated_at           DATETIME        NOT NULL,
    deleted_at           DATETIME        NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_dft_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT chk_dft_prefecture CHECK (prefecture_code IS NULL OR prefecture_code REGEXP '^[0-9]{2}$'),
    CONSTRAINT chk_dft_scope_type CHECK (scope_type IS NULL OR scope_type IN ('ORGANIZATION')),
    CONSTRAINT chk_dft_system_scope CHECK (
        (is_system_template = 1 AND scope_type IS NULL AND scope_id IS NULL)
        OR (is_system_template = 0 AND scope_type IS NOT NULL AND scope_id IS NOT NULL)
    ),
    UNIQUE KEY uq_dft_code_version (code, version, deleted_at),
    INDEX idx_dft_prefecture (prefecture_code, is_active, effective_from),
    INDEX idx_dft_scope (scope_type, scope_id, is_active),
    INDEX idx_dft_standard (is_standard, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
