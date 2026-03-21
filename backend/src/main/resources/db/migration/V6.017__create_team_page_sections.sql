-- F06.2 ページ内セクション
CREATE TABLE team_page_sections (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_page_id  BIGINT UNSIGNED NOT NULL,
    section_type  ENUM('TEXT', 'IMAGE', 'MEMBER_LIST', 'HEADING') NOT NULL,
    title         VARCHAR(200)    NULL,
    content       TEXT            NULL,
    image_s3_key  VARCHAR(500)    NULL,
    image_caption VARCHAR(200)    NULL,
    sort_order    INT             NOT NULL DEFAULT 0,
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_tps_page_order (team_page_id, sort_order),
    CONSTRAINT fk_tps_team_page FOREIGN KEY (team_page_id) REFERENCES team_pages (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
