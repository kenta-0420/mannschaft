-- CMP-260827-1808: 現役所属の一意と USER の参加枠（枠番号の天井 1000。上限の既定 100 はアプリ設定）を DB 制約で保証する。
--
-- 既存データの扱い（御裁可済み B案）: 異常データがあっても移行を止めず、この SQL の中で自動是正する。
--   (1) 同村・同主体の現役所属の重複 … 1件だけ残し、残りに left_at を設定して退村扱いにする。
--   (2) USER の現役所属が100村を超える … 100件だけ残し、超過分に left_at を設定して退村扱いにする。
-- 残す行の順位: joined_at が古い順、同値なら id が小さい順（BAN の有無は順位に使わない）。
--   BAN 済みの所属を退村扱いにしても banned_at は残り、再参加の拒否は left_at を問わず
--   banned_at の行で判定するため、BAN が外れることはない。
-- 退村扱いにした行は role・BAN・履歴列を変更せず、left_at / updated_at / version だけを更新する。
-- 是正件数は途中の SELECT（cmp1808_fixed_duplicates / cmp1808_fixed_overflow）で Flyway のログに出す。
-- 旧 uk_vm_village_subject の重複を避けるため、重複是正の退村時刻は行ごとに1秒ずつずらす。
--
-- 再適用（冪等性）: MySQL の ALTER TABLE は暗黙 commit するため、途中で失敗すると是正済みデータと
-- 追加済みの列・制約が残る。各 DDL は information_schema で存在を確かめてから実行し、
-- 失敗行を flyway repair で消したあと同じ移行をそのまま再適用できるようにしている。
-- 本番前の点検 SQL と停止窓の手順は docs/features/F17.1_village_community.md「USER 参加枠の移行手順」を参照。

SET @cmp1808_left_base = UTC_TIMESTAMP(6);

-- (1) 現役所属の重複を是正する。window 結果を一時表へ確定させ、同表 UPDATE の制限を避ける。
DROP TEMPORARY TABLE IF EXISTS cmp1808_duplicate_fix;
CREATE TEMPORARY TABLE cmp1808_duplicate_fix AS
SELECT id, rn FROM (
    SELECT id, ROW_NUMBER() OVER (
        PARTITION BY village_id, subject_type, subject_id
        ORDER BY joined_at, id) AS rn
    FROM village_memberships
    WHERE left_at IS NULL
) ranked
WHERE rn > 1;

UPDATE village_memberships m
JOIN cmp1808_duplicate_fix f ON f.id = m.id
SET m.left_at = @cmp1808_left_base + INTERVAL f.rn SECOND,
    m.updated_at = UTC_TIMESTAMP(6),
    m.version = m.version + 1;

-- (2) USER の100村超過を是正する（(1) の後の現役所属が対象）。
DROP TEMPORARY TABLE IF EXISTS cmp1808_overflow_fix;
CREATE TEMPORARY TABLE cmp1808_overflow_fix AS
SELECT id FROM (
    SELECT id, ROW_NUMBER() OVER (
        PARTITION BY subject_id
        ORDER BY joined_at, id) AS rn
    FROM village_memberships
    WHERE subject_type = 'USER' AND left_at IS NULL
) ranked
WHERE rn > 100;

UPDATE village_memberships m
JOIN cmp1808_overflow_fix f ON f.id = m.id
SET m.left_at = @cmp1808_left_base,
    m.updated_at = UTC_TIMESTAMP(6),
    m.version = m.version + 1;

-- 是正件数を Flyway のログへ出す。
SELECT (SELECT COUNT(*) FROM cmp1808_duplicate_fix) AS cmp1808_fixed_duplicates,
       (SELECT COUNT(*) FROM cmp1808_overflow_fix) AS cmp1808_fixed_overflow;

DROP TEMPORARY TABLE cmp1808_duplicate_fix;
DROP TEMPORARY TABLE cmp1808_overflow_fix;

-- user_slot 列（無ければ追加）
SET @cmp1808_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'village_memberships' AND column_name = 'user_slot') = 0, 'ALTER TABLE village_memberships ADD COLUMN user_slot SMALLINT UNSIGNED NULL COMMENT ''USERの在籍村slot。現役は1..天井（1000）、退村履歴は保持可''', 'DO 0');
PREPARE cmp1808_stmt FROM @cmp1808_ddl;
EXECUTE cmp1808_stmt;
DEALLOCATE PREPARE cmp1808_stmt;

-- slot の backfill は、USER 枠の一意キーがまだ無いときだけ行う（張り終えた後の再適用で番号を振り直さない）。
SET @cmp1808_slot_uk_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'village_memberships' AND index_name = 'uk_vm_user_active_slot');

DROP TEMPORARY TABLE IF EXISTS cmp1808_user_slot_backfill;
CREATE TEMPORARY TABLE cmp1808_user_slot_backfill AS
SELECT id, ROW_NUMBER() OVER (PARTITION BY subject_id ORDER BY id) AS user_slot
FROM village_memberships
WHERE subject_type = 'USER' AND left_at IS NULL;

UPDATE village_memberships m
JOIN cmp1808_user_slot_backfill b ON b.id = m.id
SET m.user_slot = b.user_slot, m.updated_at = m.updated_at
WHERE @cmp1808_slot_uk_exists = 0;
DROP TEMPORARY TABLE cmp1808_user_slot_backfill;

-- 枠番号の天井（1000）と、(2) の超過是正のしきい値（既定の上限 100）は Java の
-- VillageParticipationLimits.DB_SLOT_CEILING / DEFAULT_HARD_LIMIT と一致させる（照合は
-- VillageParticipationLimitsMigrationConsistencyTest）。実際の上限は設定で天井以下に変えられ、
-- 上限の判定はアプリ（VillageParticipationLimitProvider）が行う。DB は天井と枠番号の一意だけを保証する。
-- generated 式の base は FK 付き village_id / subject_id を含めない。
-- 現役 BAN も一意・容量の対象。退村履歴と TEAM/ORGANIZATION は USER の slot を消費しない。
SET @cmp1808_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'village_memberships' AND column_name = 'active_user_slot') = 0, 'ALTER TABLE village_memberships ADD COLUMN active_user_slot SMALLINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN subject_type = ''USER'' AND left_at IS NULL THEN user_slot ELSE NULL END) VIRTUAL', 'DO 0');
PREPARE cmp1808_stmt FROM @cmp1808_ddl;
EXECUTE cmp1808_stmt;
DEALLOCATE PREPARE cmp1808_stmt;

SET @cmp1808_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'village_memberships' AND column_name = 'active_marker') = 0, 'ALTER TABLE village_memberships ADD COLUMN active_marker TINYINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN left_at IS NULL THEN 1 ELSE NULL END) VIRTUAL', 'DO 0');
PREPARE cmp1808_stmt FROM @cmp1808_ddl;
EXECUTE cmp1808_stmt;
DEALLOCATE PREPARE cmp1808_stmt;

SET @cmp1808_ddl = IF((SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = DATABASE() AND table_name = 'village_memberships' AND constraint_name = 'ck_vm_active_user_slot') = 0, 'ALTER TABLE village_memberships ADD CONSTRAINT ck_vm_active_user_slot CHECK (subject_type <> ''USER'' OR left_at IS NOT NULL OR (user_slot IS NOT NULL AND user_slot BETWEEN 1 AND 1000))', 'DO 0');
PREPARE cmp1808_stmt FROM @cmp1808_ddl;
EXECUTE cmp1808_stmt;
DEALLOCATE PREPARE cmp1808_stmt;

SET @cmp1808_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'village_memberships' AND index_name = 'uk_vm_user_active_slot') = 0, 'ALTER TABLE village_memberships ADD UNIQUE KEY uk_vm_user_active_slot (subject_id, active_user_slot)', 'DO 0');
PREPARE cmp1808_stmt FROM @cmp1808_ddl;
EXECUTE cmp1808_stmt;
DEALLOCATE PREPARE cmp1808_stmt;

SET @cmp1808_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'village_memberships' AND index_name = 'uk_vm_active_subject') = 0, 'ALTER TABLE village_memberships ADD UNIQUE KEY uk_vm_active_subject (village_id, subject_type, subject_id, active_marker)', 'DO 0');
PREPARE cmp1808_stmt FROM @cmp1808_ddl;
EXECUTE cmp1808_stmt;
DEALLOCATE PREPARE cmp1808_stmt;

-- 入村を USER 単位で直列化するロック行（村ドメイン内。auth の users 行をロックすると村の取引が越境するため）。
-- 行は入村時に INSERT ... ON DUPLICATE KEY UPDATE で作成・ロックする。user_id に FK は張らない（原則1）。
CREATE TABLE IF NOT EXISTS village_user_admission_locks (
    id        BINARY(16)      NOT NULL COMMENT 'UUIDv7 PK',
    user_id   BIGINT UNSIGNED NOT NULL COMMENT '入村を直列化するUSER（FK 張らない / 原則1）',
    locked_at DATETIME(6)     NOT NULL COMMENT '最後に入村ロックを取った時刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_vual_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='村入村のUSER単位直列化ロック行（CMP-260827-1808）';

-- PK・村 CASCADE FK・代表委任の下流 FK・旧 uk_vm_village_subject は変更しない。
-- 旧キーは退村履歴の同時刻重複を防ぐ役割が残るため存置する（uk_vm_active_subject は現役のみが対象）。
