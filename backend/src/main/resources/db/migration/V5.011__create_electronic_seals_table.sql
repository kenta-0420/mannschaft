-- F05.3 電子印鑑: electronic_seals テーブル
CREATE TABLE electronic_seals (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    variant     VARCHAR(20)  NOT NULL,
    display_text VARCHAR(20) NOT NULL,
    svg_data    TEXT         NOT NULL,
    seal_hash   CHAR(64)     NOT NULL,
    generation_version INT   NOT NULL DEFAULT 1,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at  DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_electronic_seals_user_variant (user_id, variant),
    CONSTRAINT fk_electronic_seals_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
