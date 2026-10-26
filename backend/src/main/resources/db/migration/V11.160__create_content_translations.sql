CREATE TABLE IF NOT EXISTS content_translations
(
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type         VARCHAR(50)   NOT NULL,
    scope_id           BIGINT UNSIGNED NOT NULL,
    source_type        VARCHAR(50)   NOT NULL COMMENT 'BLOG_POST/ANNOUNCEMENT/KNOWLEDGE_BASE',
    source_id          BIGINT UNSIGNED NOT NULL,
    language           VARCHAR(10)   NOT NULL,
    translated_title   VARCHAR(300)  NULL,
    translated_body    MEDIUMTEXT    NULL,
    translated_excerpt VARCHAR(1000) NULL,
    status             VARCHAR(20)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/IN_REVIEW/PUBLISHED/NEEDS_UPDATE',
    translator_id      BIGINT UNSIGNED NULL,
    reviewer_id        BIGINT UNSIGNED NULL,
    source_updated_at  DATETIME      NOT NULL COMMENT '原文のupdated_atスナップショット',
    published_at       DATETIME      NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at         DATETIME      NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_ct_source_lang UNIQUE (source_type, source_id, language, deleted_at),
    CONSTRAINT fk_ct_translator FOREIGN KEY (translator_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_ct_reviewer FOREIGN KEY (reviewer_id) REFERENCES users (id) ON DELETE SET NULL,
    INDEX idx_ct_scope (scope_type, scope_id, status),
    INDEX idx_ct_source (source_type, source_id),
    INDEX idx_ct_translator (translator_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
