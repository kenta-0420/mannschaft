-- F10.1 フィーチャーフラグテーブル
CREATE TABLE feature_flags (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    flag_key    VARCHAR(100)  NOT NULL,
    is_enabled  BOOLEAN       NOT NULL DEFAULT FALSE,
    description VARCHAR(500)  NULL,
    updated_by  BIGINT UNSIGNED NULL,
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_feature_flags_key (flag_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
