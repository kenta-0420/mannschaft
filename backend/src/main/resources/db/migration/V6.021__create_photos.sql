-- F06.2 個別写真
CREATE TABLE photos (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    album_id          BIGINT UNSIGNED NOT NULL,
    s3_key            VARCHAR(500)    NOT NULL,
    thumbnail_s3_key  VARCHAR(500)    NULL,
    original_filename VARCHAR(255)    NOT NULL,
    content_type      VARCHAR(50)     NOT NULL,
    file_size         INT UNSIGNED    NOT NULL,
    width             INT UNSIGNED    NULL,
    height            INT UNSIGNED    NULL,
    caption           VARCHAR(300)    NULL,
    taken_at          DATETIME        NULL,
    sort_order        INT             NOT NULL DEFAULT 0,
    uploaded_by       BIGINT UNSIGNED NULL,
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_ph_album_order (album_id, sort_order),
    INDEX idx_ph_album_taken (album_id, taken_at),
    INDEX idx_ph_uploaded_by (uploaded_by),
    CONSTRAINT fk_ph_album FOREIGN KEY (album_id) REFERENCES photo_albums (id) ON DELETE CASCADE,
    CONSTRAINT fk_ph_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
