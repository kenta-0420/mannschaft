-- F04.1 タイムライン投稿添付ファイルテーブル
CREATE TABLE timeline_post_attachments (
    id                  BIGINT UNSIGNED        NOT NULL AUTO_INCREMENT,
    timeline_post_id    BIGINT UNSIGNED        NOT NULL,
    attachment_type     VARCHAR(20)   NOT NULL,
    file_key            VARCHAR(500)  NULL,
    original_filename   VARCHAR(255)  NULL,
    file_size           INT           NULL,
    mime_type           VARCHAR(100)  NULL,
    image_width         SMALLINT      NULL,
    image_height        SMALLINT      NULL,
    video_url           VARCHAR(2048) NULL,
    video_thumbnail_url VARCHAR(2048) NULL,
    video_title         VARCHAR(500)  NULL,
    link_url            VARCHAR(2048) NULL,
    og_title            VARCHAR(500)  NULL,
    og_description      VARCHAR(1000) NULL,
    og_image_url        VARCHAR(2048) NULL,
    og_site_name        VARCHAR(200)  NULL,
    sort_order          SMALLINT      NOT NULL DEFAULT 0,
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_post_attachments_post FOREIGN KEY (timeline_post_id) REFERENCES timeline_posts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
