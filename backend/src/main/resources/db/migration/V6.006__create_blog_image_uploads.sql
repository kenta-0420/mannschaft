-- F06.1: ブログ画像管理テーブル（S3アップロード追跡・孤立画像クリーンアップ用）
CREATE TABLE blog_image_uploads (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    blog_post_id BIGINT UNSIGNED NULL,
    uploader_id  BIGINT UNSIGNED NULL,
    s3_key       VARCHAR(500) NOT NULL,
    file_size    INT UNSIGNED NOT NULL,
    content_type VARCHAR(50) NOT NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_biu_post (blog_post_id),
    INDEX idx_biu_orphan (blog_post_id, created_at),
    UNIQUE KEY uq_biu_s3_key (s3_key),
    CONSTRAINT fk_biu_post FOREIGN KEY (blog_post_id) REFERENCES blog_posts (id) ON DELETE SET NULL,
    CONSTRAINT fk_biu_uploader FOREIGN KEY (uploader_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
