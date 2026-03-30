-- Webhookエンドポイントテーブル
CREATE TABLE webhook_endpoints (
    id                       BIGINT        NOT NULL AUTO_INCREMENT,
    scope_type               VARCHAR(50)   NOT NULL,
    scope_id                 BIGINT        NOT NULL,
    name                     VARCHAR(100)  NOT NULL,
    url                      VARCHAR(1000) NOT NULL COMMENT 'HTTPS必須',
    signing_secret           VARCHAR(64)   NOT NULL COMMENT 'HMAC-SHA256署名用シークレット',
    is_active                TINYINT(1)    NOT NULL DEFAULT 1,
    timeout_ms               INT           NOT NULL DEFAULT 5000,
    consecutive_failure_count INT          NOT NULL DEFAULT 0 COMMENT '連続失敗回数',
    last_failure_at          DATETIME      NULL,
    created_by               BIGINT        NOT NULL,
    version                  BIGINT        NOT NULL DEFAULT 0,
    created_at               DATETIME      NOT NULL,
    updated_at               DATETIME      NOT NULL,
    deleted_at               DATETIME      NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_we_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    INDEX idx_we_scope (scope_type, scope_id, is_active)
);
