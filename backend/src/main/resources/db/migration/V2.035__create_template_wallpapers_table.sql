-- F01.4: テンプレート別壁紙プリセット
CREATE TABLE template_wallpapers (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    template_slug  VARCHAR(50)     NOT NULL COMMENT 'テンプレート識別子（* = 全テンプレート共通）',
    name           VARCHAR(100)    NOT NULL COMMENT '壁紙名',
    image_url      VARCHAR(500)    NOT NULL COMMENT 'S3画像URL',
    thumbnail_url  VARCHAR(500)    NOT NULL COMMENT 'サムネイルURL',
    category       VARCHAR(50)     NOT NULL DEFAULT 'DEFAULT' COMMENT 'DEFAULT / SEASONAL / FUN',
    sort_order     INTEGER         NOT NULL DEFAULT 0,
    is_active      BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_tw_template (template_slug, is_active, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
