-- F09.8: コルクボードセクション（グループ）
CREATE TABLE corkboard_groups (
    id              BIGINT UNSIGNED     NOT NULL AUTO_INCREMENT,
    corkboard_id    BIGINT UNSIGNED     NOT NULL,
    name            VARCHAR(100)        NOT NULL,
    is_collapsed    BOOLEAN             NOT NULL DEFAULT FALSE,
    position_x      INT                 NOT NULL DEFAULT 0,
    position_y      INT                 NOT NULL DEFAULT 0,
    width           INT                 NOT NULL DEFAULT 400,
    height          INT                 NOT NULL DEFAULT 300,
    display_order   SMALLINT UNSIGNED   NOT NULL DEFAULT 0,
    created_at      DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_cg_board (corkboard_id, display_order),
    CONSTRAINT fk_cg_corkboard FOREIGN KEY (corkboard_id) REFERENCES corkboards (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
