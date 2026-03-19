-- モジュール定義マスターテーブル
CREATE TABLE module_definitions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    slug VARCHAR(50) NOT NULL,
    description VARCHAR(500) NULL,
    module_type VARCHAR(20) NOT NULL,
    module_number INT NOT NULL,
    requires_paid_plan TINYINT(1) NOT NULL DEFAULT 0,
    feature_flag VARCHAR(50) NULL,
    trial_days INT NULL DEFAULT 0,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_module_definitions_slug UNIQUE (slug),
    CONSTRAINT chk_module_type CHECK (module_type IN ('DEFAULT','OPTIONAL'))
);
