-- CMP-260923-0953: 親削除時の子データを保全し、既存の孤児にも親の削除日時をコピーする。
-- 行・ID・業務値・更新日時は変更しない。件数は環境ごとの実データに従う。
ALTER TABLE shift_slots ADD COLUMN deleted_at DATETIME NULL;
ALTER TABLE shift_requests
    ADD COLUMN deleted_at DATETIME NULL,
    ADD COLUMN delete_reason VARCHAR(20) NULL,
    ADD COLUMN active_uq TINYINT
        GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) VIRTUAL;
ALTER TABLE shift_assignments ADD COLUMN deleted_at DATETIME NULL;

ALTER TABLE shift_requests
    DROP INDEX uq_sr_schedule_user_slot,
    ADD CONSTRAINT uq_sr_schedule_user_slot_active
        UNIQUE (schedule_id, user_id, slot_id_uq, slot_date_uq, active_uq);

-- 削除済み枠も含めて割当履歴を取り残さないよう、通常ORMフィルタを経由しない。
UPDATE shift_assignments a
JOIN shift_slots s ON s.id = a.slot_id
JOIN shift_schedules sc ON sc.id = s.schedule_id
SET a.deleted_at = sc.deleted_at, a.updated_at = a.updated_at
WHERE sc.deleted_at IS NOT NULL AND a.deleted_at IS NULL;

UPDATE shift_requests r
JOIN shift_schedules sc ON sc.id = r.schedule_id
SET r.deleted_at = sc.deleted_at,
    r.delete_reason = 'PARENT_DELETED',
    r.updated_at = r.updated_at
WHERE sc.deleted_at IS NOT NULL AND r.deleted_at IS NULL;

UPDATE shift_slots s
JOIN shift_schedules sc ON sc.id = s.schedule_id
SET s.deleted_at = sc.deleted_at, s.updated_at = s.updated_at
WHERE sc.deleted_at IS NOT NULL AND s.deleted_at IS NULL;
