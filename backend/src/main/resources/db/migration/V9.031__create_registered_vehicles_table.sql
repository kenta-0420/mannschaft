-- F09.3 登録車両
CREATE TABLE registered_vehicles (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id           BIGINT UNSIGNED NOT NULL,
    vehicle_type      ENUM('CAR','MOTORCYCLE','BICYCLE') NOT NULL,
    plate_number      VARBINARY(512)  NOT NULL,
    plate_number_hash CHAR(64)        NOT NULL,
    nickname          VARCHAR(50)     NULL,
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at        DATETIME        NULL,
    PRIMARY KEY (id),
    INDEX idx_rv_user (user_id, deleted_at),
    UNIQUE KEY uq_rv_plate_hash (plate_number_hash, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
