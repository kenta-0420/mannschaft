-- F04.4 ソーシャルプロフィールテーブル
CREATE TABLE user_social_profiles (
    id           BIGINT UNSIGNED       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT UNSIGNED NOT NULL,
    handle       VARCHAR(30)  NOT NULL,
    display_name VARCHAR(50)  NULL,
    avatar_url   VARCHAR(500) NULL,
    bio          VARCHAR(300) NULL,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_social_profiles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_social_profiles_handle (handle)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
