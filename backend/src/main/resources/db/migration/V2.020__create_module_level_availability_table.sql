-- モジュール×レベル別利用可否テーブル
CREATE TABLE module_level_availability (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    module_id BIGINT UNSIGNED NOT NULL,
    level VARCHAR(20) NOT NULL,
    is_available TINYINT(1) NOT NULL DEFAULT 1,
    note VARCHAR(200) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_module_level UNIQUE (module_id, level),
    CONSTRAINT fk_module_level_module FOREIGN KEY (module_id) REFERENCES module_definitions(id) ON DELETE CASCADE,
    CONSTRAINT chk_module_level CHECK (level IN ('ORGANIZATION','TEAM','PERSONAL'))
);
