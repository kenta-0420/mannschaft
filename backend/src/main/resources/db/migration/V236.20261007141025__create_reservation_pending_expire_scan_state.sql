-- CMP1730: 全runner共通の有界失効進捗をUUIDv7主キーで保持する。
-- UUIDv7の48bit epoch/version/variant生成は既V212/V215方式を再利用する。
CREATE TABLE reservation_pending_expire_scan_state (
    id BINARY(16) NOT NULL,
    singleton_key TINYINT NOT NULL,
    cycle_high_water BIGINT UNSIGNED NOT NULL,
    last_inspected_id BIGINT UNSIGNED NOT NULL,
    run_epoch BIGINT NOT NULL,
    retry_primary_ids JSON NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_rpess_singleton UNIQUE (singleton_key),
    CONSTRAINT chk_rpess_singleton CHECK (singleton_key = 1),
    CONSTRAINT chk_rpess_high_water CHECK (cycle_high_water >= 0),
    CONSTRAINT chk_rpess_cursor CHECK (
        last_inspected_id >= 0 AND last_inspected_id <= cycle_high_water
    ),
    CONSTRAINT chk_rpess_epoch CHECK (run_epoch >= 0),
    CONSTRAINT chk_rpess_retry_array CHECK (JSON_TYPE(retry_primary_ids) = 'ARRAY'),
    CONSTRAINT chk_rpess_retry_limit CHECK (JSON_LENGTH(retry_primary_ids) <= 500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET @rpess_uuid_ms = TIMESTAMPDIFF(
    MICROSECOND, '1970-01-01 00:00:00.000000', UTC_TIMESTAMP(3)
) DIV 1000;
INSERT INTO reservation_pending_expire_scan_state (
    id, singleton_key, cycle_high_water, last_inspected_id,
    run_epoch, retry_primary_ids, updated_at
) VALUES (
    UNHEX(CONCAT(
        LPAD(HEX(@rpess_uuid_ms), 12, '0'),
        '7', SUBSTRING(HEX(RANDOM_BYTES(2)), 2, 3),
        HEX(8 + (ORD(RANDOM_BYTES(1)) & 3)),
        SUBSTRING(HEX(RANDOM_BYTES(8)), 2, 15)
    )),
    1, 0, 0, 0, JSON_ARRAY(), UTC_TIMESTAMP(6)
);
