-- F??（障害告知バナー）: incident_banners テーブル作成
-- 管理者がシスアド画面から手動公開する障害・メンテナンス告知バナーを永続化する。
-- created_by は users ドメインのクロスドメインFK を張らない（アーキテクチャ原則1）。
CREATE TABLE incident_banners (
    id                BINARY(16)   NOT NULL,
    level             VARCHAR(10)  NOT NULL DEFAULT 'INFO',
    page_pattern      VARCHAR(255) NOT NULL DEFAULT '*',
    published         BOOLEAN      NOT NULL DEFAULT FALSE,
    original_language VARCHAR(10)  NOT NULL DEFAULT 'ja',
    starts_at         DATETIME     NULL,
    ends_at           DATETIME     NULL,
    created_by        BIGINT       NULL,
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL,
    deleted_at        DATETIME     NULL,
    PRIMARY KEY (id),
    INDEX idx_incident_banners_active (published, deleted_at, starts_at, ends_at)
);
