-- F06.2 メンバープロフィール
CREATE TABLE member_profiles (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_page_id        BIGINT UNSIGNED NOT NULL,
    user_id             BIGINT UNSIGNED NULL,
    display_name        VARCHAR(100)    NOT NULL,
    member_number       VARCHAR(20)     NULL,
    photo_s3_key        VARCHAR(500)    NULL,
    bio                 VARCHAR(500)    NULL,
    position            VARCHAR(100)    NULL,
    custom_field_values JSON            NULL,
    sort_order          INT             NOT NULL DEFAULT 0,
    is_visible          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_mp_page_order (team_page_id, sort_order),
    UNIQUE KEY uq_mp_page_user (team_page_id, user_id),
    INDEX idx_mp_user (user_id),
    INDEX idx_mp_member_number (team_page_id, member_number),
    CONSTRAINT fk_mp_team_page FOREIGN KEY (team_page_id) REFERENCES team_pages (id) ON DELETE CASCADE,
    CONSTRAINT fk_mp_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
