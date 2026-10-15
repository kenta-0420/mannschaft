-- F09.5 共用施設予約: 予約備品紐付け
CREATE TABLE facility_booking_equipment (
    id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    booking_id    BIGINT UNSIGNED NOT NULL,
    equipment_id  BIGINT UNSIGNED NOT NULL,
    quantity      SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    unit_price    DECIMAL(10,0)   NOT NULL DEFAULT 0,
    subtotal      DECIMAL(10,0)   NOT NULL DEFAULT 0,

    UNIQUE KEY uq_fbe_booking_equip (booking_id, equipment_id),
    INDEX idx_fbe_equipment (equipment_id),
    CONSTRAINT fk_fbe_booking FOREIGN KEY (booking_id) REFERENCES facility_bookings(id) ON DELETE CASCADE,
    CONSTRAINT fk_fbe_equipment FOREIGN KEY (equipment_id) REFERENCES facility_equipment(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
