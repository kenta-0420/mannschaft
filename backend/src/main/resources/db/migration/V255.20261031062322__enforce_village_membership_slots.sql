-- CMP-260827-1808: 現役所属の一意と USER 100村上限を DB 制約で保証する。
--
-- 既存データの扱い（御裁可済み B案）: 異常データがあっても移行を止めず、この SQL の中で自動是正する。
--   (1) 同村・同主体の現役所属の重複 … 1件だけ残し、残りに left_at を設定して退村扱いにする。
--   (2) USER の現役所属が100村を超える … 100件だけ残し、超過分に left_at を設定して退村扱いにする。
-- 残す行の順位: BAN 中の所属を最優先（退村扱いにすると BAN が外れて再参加できてしまうため）、
--   次に joined_at が古い順、同値なら id が小さい順。
-- 退村扱いにした行は role・BAN・履歴列を変更せず、left_at / updated_at / version だけを更新する。
-- 是正件数は末尾の SELECT（cmp1808_fixed_duplicates / cmp1808_fixed_overflow）で Flyway のログに出す。
-- 旧 uk_vm_village_subject の重複を避けるため、退村時刻は行ごとに1秒ずつずらす。

SET @cmp1808_left_base = CURRENT_TIMESTAMP(6);

-- (1) 現役所属の重複を是正する。window 結果を一時表へ確定させ、同表 UPDATE の制限を避ける。
CREATE TEMPORARY TABLE cmp1808_duplicate_fix AS
SELECT id, rn FROM (
    SELECT id, ROW_NUMBER() OVER (
        PARTITION BY village_id, subject_type, subject_id
        ORDER BY (banned_at IS NULL), joined_at, id) AS rn
    FROM village_memberships
    WHERE left_at IS NULL
) ranked
WHERE rn > 1;

UPDATE village_memberships m
JOIN cmp1808_duplicate_fix f ON f.id = m.id
SET m.left_at = @cmp1808_left_base + INTERVAL f.rn SECOND,
    m.updated_at = CURRENT_TIMESTAMP(6),
    m.version = m.version + 1;

-- (2) USER の100村超過を是正する（(1) の後の現役所属が対象）。
CREATE TEMPORARY TABLE cmp1808_overflow_fix AS
SELECT id FROM (
    SELECT id, ROW_NUMBER() OVER (
        PARTITION BY subject_id
        ORDER BY (banned_at IS NULL), joined_at, id) AS rn
    FROM village_memberships
    WHERE subject_type = 'USER' AND left_at IS NULL
) ranked
WHERE rn > 100;

UPDATE village_memberships m
JOIN cmp1808_overflow_fix f ON f.id = m.id
SET m.left_at = @cmp1808_left_base,
    m.updated_at = CURRENT_TIMESTAMP(6),
    m.version = m.version + 1;

-- 是正件数を Flyway のログへ出す。
SELECT (SELECT COUNT(*) FROM cmp1808_duplicate_fix) AS cmp1808_fixed_duplicates,
       (SELECT COUNT(*) FROM cmp1808_overflow_fix) AS cmp1808_fixed_overflow;

DROP TEMPORARY TABLE cmp1808_duplicate_fix;
DROP TEMPORARY TABLE cmp1808_overflow_fix;

ALTER TABLE village_memberships
    ADD COLUMN user_slot SMALLINT UNSIGNED NULL COMMENT 'USERの在籍村slot。現役は1..100、退村履歴は保持可';

-- 元行を変更せずに ROW_NUMBER の結果を確定し、同じ表の window 結果 UPDATE 制限を避ける。
CREATE TEMPORARY TABLE cmp1808_user_slot_backfill AS
SELECT id, ROW_NUMBER() OVER (PARTITION BY subject_id ORDER BY id) AS user_slot
FROM village_memberships
WHERE subject_type = 'USER' AND left_at IS NULL;

UPDATE village_memberships m
JOIN cmp1808_user_slot_backfill b ON b.id = m.id
SET m.user_slot = b.user_slot, m.updated_at = m.updated_at;
DROP TEMPORARY TABLE cmp1808_user_slot_backfill;

-- generated 式の base は FK 付き village_id / subject_id を含めない。
-- 現役 BAN も一意・容量の対象。退村履歴と TEAM/ORGANIZATION は USER の slot を消費しない。
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

-- PK・村 CASCADE FK・代表委任の下流 FK・旧 uk_vm_village_subject は変更しない。
-- 旧キーは退村履歴の同時刻重複を防ぐ役割が残るため存置する（uk_vm_active_subject は現役のみが対象）。
