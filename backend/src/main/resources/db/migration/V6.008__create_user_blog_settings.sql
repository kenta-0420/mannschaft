-- F06.1: ユーザーごとのブログ設定テーブル（セルフレビュー等）
CREATE TABLE user_blog_settings (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id             BIGINT UNSIGNED NOT NULL,
    self_review_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    self_review_start   TIME NOT NULL DEFAULT '23:00:00',
    self_review_end     TIME NOT NULL DEFAULT '06:00:00',
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ubs_user (user_id),
    CONSTRAINT fk_ubs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
