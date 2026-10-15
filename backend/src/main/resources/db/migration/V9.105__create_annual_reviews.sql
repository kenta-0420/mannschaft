-- F09.16 S1-B: residence-status ドメイン
-- annual_reviews（年次更新キャンペーン）
-- CLAUDE.md 原則 6 適用（新規テーブル UUIDv7 主キー）
CREATE TABLE annual_reviews (
    id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    review_year SMALLINT UNSIGNED NOT NULL,
    started_at DATETIME(6) NOT NULL,
    deadline_at DATETIME(6) NOT NULL,
    closed_at DATETIME(6) NULL,
    target_count INT UNSIGNED NOT NULL DEFAULT 0,
    response_count INT UNSIGNED NOT NULL DEFAULT 0,
    created_by BIGINT UNSIGNED NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ar_org_year (organization_id, review_year, deleted_at),
    INDEX idx_ar_org (organization_id, deleted_at),
    INDEX idx_ar_deadline (deadline_at, closed_at),
    CONSTRAINT chk_ar_review_year CHECK (review_year BETWEEN 2000 AND 2200)
);
