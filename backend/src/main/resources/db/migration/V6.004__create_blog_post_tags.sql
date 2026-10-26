-- F06.1: ブログ記事とタグの中間テーブル
CREATE TABLE blog_post_tags (
    blog_post_id BIGINT UNSIGNED NOT NULL,
    blog_tag_id  BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (blog_post_id, blog_tag_id),
    INDEX idx_bpt_tag (blog_tag_id),
    CONSTRAINT fk_bpt_post FOREIGN KEY (blog_post_id) REFERENCES blog_posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_bpt_tag FOREIGN KEY (blog_tag_id) REFERENCES blog_tags (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
