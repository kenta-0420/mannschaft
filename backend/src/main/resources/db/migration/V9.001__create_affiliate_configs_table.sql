-- =====================================================================
-- F09.7 広告表示: アフィリエイト設定テーブル
-- =====================================================================

CREATE TABLE affiliate_configs (
    id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    provider         VARCHAR(20)      NOT NULL COMMENT 'AMAZON / RAKUTEN',
    tag_id           VARCHAR(100)     NOT NULL COMMENT 'アフィリエイトタグID',
    placement        VARCHAR(30)      NOT NULL COMMENT 'SIDEBAR_RIGHT / BANNER_FOOTER / BANNER_HEADER',
    description      VARCHAR(200)     NULL     COMMENT '管理者メモ',
    banner_image_url VARCHAR(500)     NULL     COMMENT 'バナー画像URL',
    banner_width     SMALLINT UNSIGNED NULL    COMMENT 'バナー幅px',
    banner_height    SMALLINT UNSIGNED NULL    COMMENT 'バナー高さpx',
    alt_text         VARCHAR(200)     NULL     COMMENT '代替テキスト',
    is_active        BOOLEAN          NOT NULL DEFAULT TRUE  COMMENT '有効/無効',
    active_from      DATETIME         NULL     COMMENT '有効開始日時',
    active_until     DATETIME         NULL     COMMENT '有効終了日時',
    display_priority SMALLINT UNSIGNED NOT NULL DEFAULT 0    COMMENT '表示優先度（昇順）',
    created_at       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at       DATETIME         NULL     COMMENT '論理削除',

    INDEX idx_affiliate_configs_provider_placement (provider, placement),
    INDEX idx_affiliate_configs_placement_active_priority (placement, is_active, display_priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='アフィリエイト広告設定';
