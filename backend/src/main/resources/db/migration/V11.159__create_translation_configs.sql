CREATE TABLE IF NOT EXISTS translation_configs
(
    id                             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type                     VARCHAR(50)  NOT NULL,
    scope_id                       BIGINT UNSIGNED NOT NULL,
    primary_language               VARCHAR(10)  NOT NULL DEFAULT 'ja' COMMENT '原文言語',
    enabled_languages              JSON         NOT NULL DEFAULT ('[]') COMMENT '有効な翻訳対象言語コードの配列',
    is_auto_detect_reader_language TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'Accept-Language自動検出',
    version                        BIGINT       NOT NULL DEFAULT 0,
    created_at                     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_tc_scope UNIQUE (scope_type, scope_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
