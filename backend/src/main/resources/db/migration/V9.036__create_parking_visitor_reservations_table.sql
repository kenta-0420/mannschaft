-- F09.3 来場者予約
CREATE TABLE parking_visitor_reservations (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    space_id          BIGINT UNSIGNED NOT NULL,
    reserved_by       BIGINT UNSIGNED NOT NULL,
    visitor_name      VARCHAR(100)    NULL,
    visitor_plate_number VARCHAR(30)  NULL,
    reserved_date     DATE            NOT NULL,
    time_from         TIME            NOT NULL,
    time_to           TIME            NOT NULL,
    purpose           VARCHAR(200)    NULL,
    admin_comment     VARCHAR(500)    NULL,
    approved_by       BIGINT UNSIGNED NULL,
    approved_at       DATETIME        NULL,
    status            ENUM('PENDING_APPROVAL','CONFIRMED','CHECKED_IN','COMPLETED','CANCELLED','REJECTED','NO_SHOW') NOT NULL DEFAULT 'PENDING_APPROVAL',
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_pvr_space_date (space_id, reserved_date),
    INDEX idx_pvr_user (reserved_by),
    INDEX idx_pvr_date_status (reserved_date, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
