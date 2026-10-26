-- schedule_media_uploads の主キーを、既存行を失わずに UUIDv7 へ移行する。
-- schedule_id と R2 object key は外部互換性のため変更しない。
ALTER TABLE schedule_media_uploads
    ADD COLUMN id_uuid BINARY(16) NULL AFTER id,
    ADD UNIQUE KEY uq_smu_id_uuid_backfill (id_uuid);

SET @smu_uuid_ms = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00.000000', UTC_TIMESTAMP(3)) DIV 1000;

UPDATE schedule_media_uploads
   SET id_uuid = UNHEX(CONCAT(
           LPAD(HEX(@smu_uuid_ms), 12, '0'),
           '7', SUBSTRING(HEX(RANDOM_BYTES(2)), 2, 3),
           HEX(8 + (ORD(RANDOM_BYTES(1)) & 3)),
           SUBSTRING(HEX(RANDOM_BYTES(8)), 2, 15)
       ))
 WHERE id_uuid IS NULL;

ALTER TABLE storage_usage_logs
    MODIFY COLUMN reference_id BIGINT UNSIGNED NULL,
    ADD COLUMN reference_uuid BINARY(16) NULL AFTER reference_id,
    ADD INDEX idx_sul_reference_uuid (reference_type, reference_uuid),
    ADD CONSTRAINT chk_sul_reference_present
        CHECK (reference_id IS NOT NULL OR reference_uuid IS NOT NULL);

ALTER TABLE storage_migration_errors
    MODIFY COLUMN reference_id BIGINT UNSIGNED NULL,
    ADD COLUMN reference_uuid BINARY(16) NULL AFTER reference_id,
    ADD INDEX idx_sme_reference_uuid (reference_type, reference_uuid),
    ADD CONSTRAINT chk_sme_reference_present
        CHECK (reference_id IS NOT NULL OR reference_uuid IS NOT NULL);

-- 既に削除された media を参照する監査行は numeric reference_id を保全して NULL のまま残す。
UPDATE storage_usage_logs usage_log
JOIN schedule_media_uploads media
  ON usage_log.reference_type = 'schedule_media_uploads'
 AND usage_log.reference_id = media.id
   SET usage_log.reference_uuid = media.id_uuid
 WHERE usage_log.reference_uuid IS NULL;

UPDATE storage_migration_errors migration_error
JOIN schedule_media_uploads media
  ON migration_error.reference_type = 'schedule_media_uploads'
 AND migration_error.reference_id = media.id
   SET migration_error.reference_uuid = media.id_uuid
 WHERE migration_error.reference_uuid IS NULL;

UPDATE storage_acls acl
JOIN schedule_media_uploads media
  ON acl.attachment_binding_type = 'SCHEDULE_MEDIA_UPLOAD'
 AND acl.attachment_binding_key = CAST(media.id AS CHAR)
   SET acl.attachment_binding_key = LOWER(BIN_TO_UUID(media.id_uuid));

-- id_uuid の NOT NULL 化と一意制約により backfill 完了と UUID 衝突を検査してから PK を交換する。
ALTER TABLE schedule_media_uploads
    DROP PRIMARY KEY,
    DROP COLUMN id,
    CHANGE COLUMN id_uuid id BINARY(16) NOT NULL,
    ADD PRIMARY KEY (id),
    DROP INDEX uq_smu_id_uuid_backfill;
