-- CMP-260827-1808: 現役所属一意とUSER100村の上限をDB制約で保証する。
-- 移行時は全所属writer停止窓が前提。異常データの自動統合/退村はしない。

DROP PROCEDURE IF EXISTS cmp1808_assert_membership_data;
DELIMITER $$
CREATE PROCEDURE cmp1808_assert_membership_data()
BEGIN
    IF EXISTS (
        SELECT 1 FROM village_memberships
        WHERE left_at IS NULL
        GROUP BY village_id, subject_type, subject_id
        HAVING COUNT(*) > 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'CMP1808 active membership duplicates: migration stopped before membership changes';
    END IF;
    IF EXISTS (
        SELECT 1 FROM village_memberships
        WHERE subject_type = 'USER' AND left_at IS NULL
        GROUP BY subject_id
        HAVING COUNT(*) > 100
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'CMP1808 USER participation exceeds 100: migration stopped before membership changes';
    END IF;
END$$
DELIMITER ;
CALL cmp1808_assert_membership_data();
DROP PROCEDURE cmp1808_assert_membership_data;

ALTER TABLE village_memberships
    ADD COLUMN user_slot SMALLINT UNSIGNED NULL COMMENT 'USERの在籍村slot。現役は1..100、退村履歴は保持可';

-- 元行を変更せずにROW_NUMBERの結果を確定し、同じ表のwindow結果UPDATE制限を避ける。
CREATE TEMPORARY TABLE cmp1808_user_slot_backfill AS
SELECT id, ROW_NUMBER() OVER (PARTITION BY subject_id ORDER BY id) AS user_slot
FROM village_memberships
WHERE subject_type = 'USER' AND left_at IS NULL;

UPDATE village_memberships m
JOIN cmp1808_user_slot_backfill b ON b.id = m.id
SET m.user_slot = b.user_slot, m.updated_at = m.updated_at;
DROP TEMPORARY TABLE cmp1808_user_slot_backfill;

-- generated式baseはFK付きvillage_id/subject_idを含めない。
-- 現役BANも一意/容量対象。退村歴とTEAM/ORGANIZATIONはUSERのslotを消費しない。
ALTER TABLE village_memberships
    ADD COLUMN active_user_slot SMALLINT UNSIGNED GENERATED ALWAYS AS
        (CASE WHEN subject_type = 'USER' AND left_at IS NULL THEN user_slot ELSE NULL END) VIRTUAL,
    ADD COLUMN active_marker TINYINT UNSIGNED GENERATED ALWAYS AS
        (CASE WHEN left_at IS NULL THEN 1 ELSE NULL END) VIRTUAL,
    ADD CONSTRAINT ck_vm_active_user_slot CHECK (
        subject_type <> 'USER' OR left_at IS NOT NULL OR
        (user_slot IS NOT NULL AND user_slot BETWEEN 1 AND 100)
    ),
    ADD UNIQUE KEY uk_vm_user_active_slot (subject_id, active_user_slot),
    ADD UNIQUE KEY uk_vm_active_subject (village_id, subject_type, subject_id, active_marker);

-- PK・村CASCADE FK・代表委任下流FK・旧uk_vm_village_subjectは変更しない。
