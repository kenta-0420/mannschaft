-- F09.1 住民台帳: 居住者台帳テーブル
CREATE TABLE resident_registry (
    id                  BIGINT UNSIGNED     NOT NULL AUTO_INCREMENT,
    dwelling_unit_id    BIGINT UNSIGNED     NOT NULL,
    user_id             BIGINT UNSIGNED,
    resident_type       VARCHAR(20)         NOT NULL,
    last_name           VARCHAR(50)         NOT NULL,
    first_name          VARCHAR(50)         NOT NULL,
    last_name_kana      VARCHAR(100),
    first_name_kana     VARCHAR(100),
    phone               VARCHAR(20),
    email               VARCHAR(255),
    emergency_contact   VARCHAR(200),
    move_in_date        DATE                NOT NULL,
    move_out_date       DATE,
    ownership_ratio     DECIMAL(5,4),
    is_primary          BOOLEAN             NOT NULL DEFAULT FALSE,
    is_verified         BOOLEAN             NOT NULL DEFAULT FALSE,
    verified_by         BIGINT UNSIGNED,
    verified_at         DATETIME,
    notes               TEXT,
    created_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at          DATETIME,

    PRIMARY KEY (id),
    CONSTRAINT fk_rr_dwelling_unit
        FOREIGN KEY (dwelling_unit_id) REFERENCES dwelling_units (id) ON DELETE CASCADE,
    CONSTRAINT fk_rr_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_rr_verified_by
        FOREIGN KEY (verified_by) REFERENCES users (id) ON DELETE SET NULL,
    INDEX idx_rr_unit (dwelling_unit_id, move_out_date),
    INDEX idx_rr_user (user_id),
    INDEX idx_rr_type (resident_type, is_verified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='居住者台帳';
