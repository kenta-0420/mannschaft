-- F02.1 QR会員証: チェックイン履歴テーブル
CREATE TABLE member_card_checkins (
    id                    BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    member_card_id        BIGINT UNSIGNED  NOT NULL COMMENT 'FK → member_cards',
    checkin_type          VARCHAR(20)      NOT NULL DEFAULT 'STAFF_SCAN' COMMENT 'チェックイン種別（STAFF_SCAN / SELF）',
    checked_in_by         BIGINT UNSIGNED  NULL     COMMENT 'FK → users（STAFF_SCAN時のスキャンスタッフ）',
    checkin_location_id   BIGINT UNSIGNED  NULL     COMMENT 'FK → checkin_locations（SELF時の拠点）',
    checked_in_at         DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'チェックイン日時',
    location              VARCHAR(200)     NULL     COMMENT 'チェックイン場所',
    note                  VARCHAR(500)     NULL     COMMENT 'ADMIN向けメモ',
    reservation_id        BIGINT UNSIGNED  NULL     COMMENT 'FK → reservations（Phase 3でFK追加）',
    service_record_id     BIGINT UNSIGNED  NULL     COMMENT 'FK → service_records（Phase 7でFK追加）',
    transaction_id        BIGINT UNSIGNED  NULL     COMMENT 'FK → transactions（Phase 8でFK追加）',
    created_at            DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_mcc_card (member_card_id, checked_in_at DESC),
    INDEX idx_mcc_checked_in_by (checked_in_by, checked_in_at DESC),
    INDEX idx_mcc_reservation (reservation_id),
    INDEX idx_mcc_location (checkin_location_id, checked_in_at DESC),
    CONSTRAINT fk_mcc_member_card FOREIGN KEY (member_card_id) REFERENCES member_cards (id) ON DELETE CASCADE,
    CONSTRAINT fk_mcc_checked_in_by FOREIGN KEY (checked_in_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_mcc_checkin_location FOREIGN KEY (checkin_location_id) REFERENCES checkin_locations (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='チェックイン履歴';
