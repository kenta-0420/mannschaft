-- CMP-008: migrate the private csp_reports primary key from BIGINT to UUIDv7 BINARY(16).
-- The identifier is not exposed by an API/DTO and has no foreign-key dependants.
ALTER TABLE csp_reports
    ADD COLUMN id_uuid BINARY(16) NULL,
    ADD UNIQUE KEY uq_csp_reports_uuid_backfill (id_uuid);

-- UUIDv7 layout: 48-bit Unix epoch milliseconds, version 7, RFC variant 10, random tail.
-- The temporary unique index detects a random collision before the legacy key is removed.
SET @csp_uuid_ms = TIMESTAMPDIFF(
    MICROSECOND,
    '1970-01-01 00:00:00',
    UTC_TIMESTAMP(3)
) DIV 1000;

UPDATE csp_reports
SET id_uuid = UNHEX(CONCAT(
    LPAD(HEX(@csp_uuid_ms), 12, '0'),
    '7',
    SUBSTRING(HEX(RANDOM_BYTES(2)), 2, 3),
    HEX(8 + (ORD(RANDOM_BYTES(1)) & 3)),
    SUBSTRING(HEX(RANDOM_BYTES(8)), 2, 15)
))
WHERE id_uuid IS NULL;

-- Apply while writers are stopped. NOT NULL fails safely if any row was not backfilled.
ALTER TABLE csp_reports
    DROP PRIMARY KEY,
    DROP COLUMN id,
    CHANGE COLUMN id_uuid id BINARY(16) NOT NULL,
    ADD PRIMARY KEY (id),
    DROP INDEX uq_csp_reports_uuid_backfill;
