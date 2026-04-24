-- F06.1 拡張: ブログ記事への「みたよ！」リアクションテーブル
CREATE TABLE blog_post_reactions (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    blog_post_id BIGINT UNSIGNED NOT NULL,
    user_id      BIGINT UNSIGNED NOT NULL,
    created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_bpreact_post FOREIGN KEY (blog_post_id) REFERENCES blog_posts(id) ON DELETE CASCADE,
    CONSTRAINT fk_bpreact_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uq_bpreact_post_user (blog_post_id, user_id),
    INDEX idx_bpreact_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
