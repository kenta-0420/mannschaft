-- F06.1: ブログ記事リビジョン（版管理）テーブル
CREATE TABLE blog_post_revisions (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    blog_post_id    BIGINT UNSIGNED NOT NULL,
    revision_number INT UNSIGNED NOT NULL,
    title           VARCHAR(200) NOT NULL,
    body            TEXT NOT NULL,
    editor_id       BIGINT UNSIGNED NULL,
    change_summary  VARCHAR(200) NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_bpr_post_revision (blog_post_id, revision_number),
    INDEX idx_bpr_post_date (blog_post_id, created_at DESC),
    CONSTRAINT fk_bpr_post FOREIGN KEY (blog_post_id) REFERENCES blog_posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_bpr_editor FOREIGN KEY (editor_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
