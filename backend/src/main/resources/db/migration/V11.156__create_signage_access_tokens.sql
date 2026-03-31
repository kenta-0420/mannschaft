-- デジタルサイネージ アクセストークンテーブル
CREATE TABLE signage_access_tokens (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    screen_id          BIGINT UNSIGNED NOT NULL,
    token              VARCHAR(36)  NOT NULL UNIQUE COMMENT 'UUID v4',
    name               VARCHAR(100) NOT NULL,
    is_active          TINYINT(1)   NOT NULL DEFAULT 1,
    allowed_ips        JSON         NULL COMMENT 'CIDR表記の許可IPリスト。NULLは全許可',
    last_accessed_at   DATETIME     NULL,
    last_accessed_ip   VARCHAR(45)  NULL,
    created_by         BIGINT UNSIGNED NOT NULL,
    created_at         DATETIME     NOT NULL,
    updated_at         DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_sat_screen     FOREIGN KEY (screen_id)  REFERENCES signage_screens (id) ON DELETE CASCADE,
    CONSTRAINT fk_sat_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    INDEX idx_sat_screen (screen_id),
    INDEX idx_sat_token (token)
);
