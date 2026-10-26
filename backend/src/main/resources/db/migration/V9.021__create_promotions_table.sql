-- F09.2 プロモーション配信: プロモーションテーブル
CREATE TABLE promotions (
    id                  BIGINT UNSIGNED     NOT NULL AUTO_INCREMENT,
    scope_type          VARCHAR(20)         NOT NULL,
    scope_id            BIGINT UNSIGNED     NOT NULL,
    created_by          BIGINT UNSIGNED     NOT NULL,
    title               VARCHAR(200)        NOT NULL,
    body                TEXT,
    image_url           VARCHAR(500),
    coupon_id           BIGINT UNSIGNED,
    status              ENUM('DRAFT','PENDING_APPROVAL','APPROVED','SCHEDULED','PUBLISHING','PUBLISHED','CANCELLED','FAILED') NOT NULL DEFAULT 'DRAFT',
    approved_by         BIGINT UNSIGNED,
    approved_at         DATETIME,
    scheduled_at        DATETIME,
    published_at        DATETIME,
    expires_at          DATETIME,
    target_count        INT UNSIGNED        NOT NULL DEFAULT 0,
    delivered_count     INT UNSIGNED        NOT NULL DEFAULT 0,
    opened_count        INT UNSIGNED        NOT NULL DEFAULT 0,
    skipped_count       INT UNSIGNED        NOT NULL DEFAULT 0,
    failed_count        INT UNSIGNED        NOT NULL DEFAULT 0,
    created_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at          DATETIME,

    PRIMARY KEY (id),
    CONSTRAINT fk_promotions_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_promotions_approved_by
        FOREIGN KEY (approved_by) REFERENCES users (id) ON DELETE SET NULL,
    INDEX idx_promotions_scope (scope_type, scope_id, status, created_at DESC),
    INDEX idx_promotions_status (status, scheduled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='プロモーション';
