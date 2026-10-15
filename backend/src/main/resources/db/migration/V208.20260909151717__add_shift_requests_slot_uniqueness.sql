-- F03.5 手動シフト作成 戦役A-3 / CMP-260909-1143
-- 設計: docs/features/F03.5_shift/06_manual_authoring.md §11.5.1.2
--
-- シフト希望の一意性を DB で担保する。アプリ層の「存在チェック → INSERT」は原子的でなく、
-- 同一ユーザーからの同時リクエストは両方ともチェックを通過して重複 INSERT できる
-- （試練 ShiftRequestSlotUniquenessConcurrencyIT が 8 並列で 8 行の重複を実測した）。
--
-- 一意性の単位（Codex 検分 P1 の是正後・確定形）:
--   slot_id が非 NULL → (schedule_id, user_id, slot_id)     … 枠ごとに 1 件。slot_date は含めない
--   slot_id が NULL   → (schedule_id, user_id, slot_date)   … 日単位の従来規則
--
-- 【なぜ slot_date を非 NULL 側のキーから外すのか】
--   当初は「slot_date は §11.5.1.1-2 の検証で枠の日付と一致するからキーに含めても等価」としていたが、
--   これは誤りだった。検証が効くのは<新規に提出される行>だけである。
--     (a) 掃除の対象である既存行は、その検証を一度も通っていない。
--     (b) ShiftSlotService#updateSlot（:158-183）は ShiftSlotEntity#applyUpdate（:83-85）で
--         枠の slot_date を書き換えるが、shift_requests には一切触れない（同クラスに
--         shift_requests / requestRepository への参照は 0 件）。よって枠の日付を後から変えると
--         既存の希望行の slot_date は古い日付のまま取り残される。
--   その結果 (schedule_id, user_id, slot_id) が同じで slot_date だけ違う行が 2 件並び、
--   ShiftRequestRepository#findByScheduleIdAndUserIdAndSlotId（Optional 戻り）が
--   複数行を掴んで 500 になる。DB 側の一意性もそこだけ守れない。
--
-- 【1 本の UNIQUE で 2 通りの単位をどう表現するか】
--   正規化した生成カラムを 2 本立てる。
--     slot_id_uq   = COALESCE(slot_id, 0)
--     slot_date_uq = slot_id が NULL のときだけ slot_date、非 NULL のときは番兵 '1000-01-01'
--   これで UNIQUE (schedule_id, user_id, slot_id_uq, slot_date_uq) が
--     非 NULL 側 → (schedule_id, user_id, slot_id, 番兵)  = 実質 (schedule_id, user_id, slot_id)
--     NULL 側   → (schedule_id, user_id, 0, slot_date)   = 実質 (schedule_id, user_id, slot_date)
--   の 2 通りを 1 本で表す。実機 MySQL 8.0 で全分岐（併存・別枠・同一枠別日拒否・同一日 2 件目拒否）を実測済み。
--
-- 【VIRTUAL である理由（実機 MySQL 8.0 で実測）】
--   slot_id は FK fk_sr_slot のベースカラムであり、STORED 生成カラムは載せられない
--   （ALTER TABLE が ERROR 1215: Cannot add foreign key constraint で失敗する。
--     同型の事故が V11.030 → PR #3188 で本番全滅を招いている）。
--   VIRTUAL 生成カラムは FK と併存でき、UNIQUE インデックスも張れることを実測で確認した。
--   slot_date_uq は slot_id を参照する式なので、こちらも同じ理由で VIRTUAL とする。
--   Entity 側（ShiftRequestEntity#slotIdUq / #slotDateUq）の columnDefinition と一字一句同じであること
--   （統合テストは ddl-auto: create ＋ flyway.enabled: false で走るため、
--     食い違うと本番だけが壊れる。CMP-260909-2154）。

-- ─────────────────────────────────────────────────────────────────────
-- 1. 枠と日付が食い違う既存行を、枠の日付へ揃える
--    §11.5.1.1-2 の契約（slot_date は枠の日付と一致する）へ既存データを合わせる。
--    先に揃えることで、同一枠の日付違いが「同一キーの重複」として 2 の掃除に回収される。
-- ─────────────────────────────────────────────────────────────────────
UPDATE shift_requests r
JOIN shift_slots s ON s.id = r.slot_id
SET r.slot_date = s.slot_date
WHERE r.slot_id IS NOT NULL
  AND r.slot_date <> s.slot_date;

SET @sr_date_realigned := ROW_COUNT();

-- ─────────────────────────────────────────────────────────────────────
-- 2. 既存の重複行を掃除する（制約は重複があると張れない）
--    キーは上記の確定形（非 NULL は枠、NULL は日付）。各キーで「最新の 1 件（最大 id）」を残す。
--    shift_requests は deleted_at を持たない（論理削除の器が無い）ため物理削除とする。
-- ─────────────────────────────────────────────────────────────────────
CREATE TEMPORARY TABLE tmp_sr_dup_keep AS
SELECT schedule_id,
       user_id,
       COALESCE(slot_id, 0) AS slot_key,
       CASE WHEN slot_id IS NULL THEN slot_date ELSE DATE '1000-01-01' END AS date_key,
       MAX(id) AS keep_id
FROM shift_requests
GROUP BY schedule_id,
         user_id,
         COALESCE(slot_id, 0),
         CASE WHEN slot_id IS NULL THEN slot_date ELSE DATE '1000-01-01' END
HAVING COUNT(*) > 1;

DELETE r FROM shift_requests r
JOIN tmp_sr_dup_keep k
  ON r.schedule_id = k.schedule_id
 AND r.user_id = k.user_id
 AND COALESCE(r.slot_id, 0) = k.slot_key
 AND (CASE WHEN r.slot_id IS NULL THEN r.slot_date ELSE DATE '1000-01-01' END) = k.date_key
WHERE r.id <> k.keep_id;

SET @sr_dup_removed := ROW_COUNT();

DROP TEMPORARY TABLE tmp_sr_dup_keep;

-- 掃除件数をログに残す（AC-8-08）。SQLSTATE クラス '01' は警告であり移行は継続する。
-- Flyway は SQL 警告を適用ログへ出力する。
SET @sr_dup_msg := CONCAT('V208 shift_requests cleanup: slot_date realigned rows = ', @sr_date_realigned,
                          ', duplicate removed rows = ', @sr_dup_removed);
SIGNAL SQLSTATE '01000' SET MESSAGE_TEXT = @sr_dup_msg;

-- ─────────────────────────────────────────────────────────────────────
-- 3. 生成カラム ＋ UNIQUE
-- ─────────────────────────────────────────────────────────────────────
ALTER TABLE shift_requests
    ADD COLUMN slot_id_uq BIGINT UNSIGNED AS (COALESCE(slot_id, 0)) VIRTUAL NOT NULL
        COMMENT '一意性用の正規化 slot_id（日単位希望は 0）';

ALTER TABLE shift_requests
    ADD COLUMN slot_date_uq DATE AS (CASE WHEN slot_id IS NULL THEN slot_date ELSE DATE '1000-01-01' END)
        VIRTUAL NOT NULL
        COMMENT '一意性用の正規化 slot_date（枠指定の希望は番兵 1000-01-01。枠の一意性に日付を含めないため）';

ALTER TABLE shift_requests
    ADD CONSTRAINT uq_sr_schedule_user_slot
        UNIQUE (schedule_id, user_id, slot_id_uq, slot_date_uq);
