-- F03.5 手動シフト作成 戦役A-3 / CMP-260909-1143
-- 設計: docs/features/F03.5_shift/06_manual_authoring.md §11.5.1.2
--
-- シフト希望の一意性を DB で担保する。アプリ層の「存在チェック → INSERT」は原子的でなく、
-- 同一ユーザーからの同時リクエストは両方ともチェックを通過して重複 INSERT できる
-- （試練 ShiftRequestSlotUniquenessConcurrencyIT が 8 並列で 8 行の重複を実測した）。
--
-- 一意性の単位:
--   slot_id が非 NULL → (schedule_id, user_id, slot_id)     … 枠ごとに 1 件
--   slot_id が NULL   → (schedule_id, user_id, slot_date)   … 日単位の従来規則
-- MySQL の UNIQUE は NULL を互いに異なる値として扱うため、COALESCE(slot_id, 0) の
-- 生成カラム slot_id_uq を噛ませてから UNIQUE を張る。
--
-- 【VIRTUAL である理由（実機 MySQL 8.0 で実測）】
--   slot_id は FK fk_sr_slot のベースカラムであり、STORED 生成カラムは載せられない
--   （ALTER TABLE が ERROR 1215: Cannot add foreign key constraint で失敗する。
--     同型の事故が V11.030 → PR #3188 で本番全滅を招いている）。
--   VIRTUAL 生成カラムは FK と併存でき、UNIQUE インデックスも張れることを実測で確認した。
--   Entity 側（ShiftRequestEntity#slotIdUq）の columnDefinition と一字一句同じであること
--   （統合テストは ddl-auto: create ＋ flyway.enabled: false で走るため、
--     食い違うと本番だけが壊れる。CMP-260909-2154）。

-- ─────────────────────────────────────────────────────────────────────
-- 1. 既存の重複行を掃除する（制約は重複があると張れない）
--    各キーで「最新の 1 件（最大 id）」を残し、他を削除する。
--    shift_requests は deleted_at を持たない（論理削除の器が無い）ため物理削除とする。
-- ─────────────────────────────────────────────────────────────────────
CREATE TEMPORARY TABLE tmp_sr_dup_keep AS
SELECT schedule_id,
       user_id,
       COALESCE(slot_id, 0) AS slot_key,
       slot_date,
       MAX(id) AS keep_id
FROM shift_requests
GROUP BY schedule_id, user_id, COALESCE(slot_id, 0), slot_date
HAVING COUNT(*) > 1;

DELETE r FROM shift_requests r
JOIN tmp_sr_dup_keep k
  ON r.schedule_id = k.schedule_id
 AND r.user_id = k.user_id
 AND COALESCE(r.slot_id, 0) = k.slot_key
 AND r.slot_date = k.slot_date
WHERE r.id <> k.keep_id;

SET @sr_dup_removed := ROW_COUNT();

DROP TEMPORARY TABLE tmp_sr_dup_keep;

-- 掃除件数をログに残す（AC-8-08）。SQLSTATE クラス '01' は警告であり移行は継続する。
-- Flyway は SQL 警告を適用ログへ出力する。
SET @sr_dup_msg := CONCAT('V208 shift_requests duplicate cleanup: removed rows = ', @sr_dup_removed);
SIGNAL SQLSTATE '01000' SET MESSAGE_TEXT = @sr_dup_msg;

-- ─────────────────────────────────────────────────────────────────────
-- 2. 生成カラム ＋ UNIQUE
-- ─────────────────────────────────────────────────────────────────────
ALTER TABLE shift_requests
    ADD COLUMN slot_id_uq BIGINT UNSIGNED AS (COALESCE(slot_id, 0)) VIRTUAL NOT NULL
        COMMENT '一意性用の正規化 slot_id（日単位希望は 0）';

ALTER TABLE shift_requests
    ADD CONSTRAINT uq_sr_schedule_user_slot
        UNIQUE (schedule_id, user_id, slot_id_uq, slot_date);
