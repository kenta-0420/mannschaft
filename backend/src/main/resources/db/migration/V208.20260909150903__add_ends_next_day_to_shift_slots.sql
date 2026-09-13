-- F03.5 手動シフト作成 戦役A-1
-- 設計: docs/features/F03.5_shift/06_manual_authoring.md §11.2.5 規則5
--
-- シフト枠の日跨ぎフラグ。日跨ぎは end_time < start_time の暗黙表現ではなく本フラグで明示する。
--
-- 【一律 FALSE で済ませてはならない理由（検分 P1）】
--   既存スキーマは日跨ぎ枠を許容しており（01_db_design.md の slot_date 説明が
--   「22:00-06:00 のスロットは slot_date = 開始日」と明記している）、
--   既存行に end_time < start_time の日跨ぎ枠が入っている可能性がある。
--   そこへ一律 FALSE を書くと意味が反転し、当該行の時刻を部分更新した瞬間に
--   INVALID_TIME_RANGE で拒否される（既存値と合成して検証するため）。
--   よって追加後に end_time < start_time の行を TRUE へバックフィルする。
--
-- 【end_time = start_time（枠長ゼロ）の既存行を TRUE にしない理由】
--   TRUE にすると「24 時間枠」を意味することになり、実在しない 24 時間労働を
--   勤務時間・人件費の集計へ持ち込む（設計 §11.2.5 規則4 は 24 時間ちょうどを禁じている）。
--   FALSE のまま残せば「書き込み時のみ検証する」方針どおり、時刻を触らない限り現状維持で、
--   時刻を触るときに初めて 400 で是正を促せる。データを黙って作り変えないほうを採る。
--   なお開発 DB の実測では該当行は 0 件（shift_slots 258 行中、日跨ぎ 0・枠長ゼロ 0）。

ALTER TABLE shift_slots
    ADD COLUMN ends_next_day BOOLEAN NOT NULL DEFAULT FALSE COMMENT '翌日終了（日跨ぎ）か' AFTER end_time;

-- 既存の日跨ぎ行（end_time < start_time）を TRUE へ移行する。
UPDATE shift_slots
   SET ends_next_day = TRUE
 WHERE end_time < start_time;

SET @ssl_cross_day_migrated := ROW_COUNT();

-- 移行件数をログに残す。SQLSTATE クラス '01' は警告であり移行は継続する
-- （Flyway は SQL 警告を適用ログへ出力する）。
SET @ssl_cross_day_msg := CONCAT(
    'V208 shift_slots ends_next_day backfill: rows set TRUE = ', @ssl_cross_day_migrated);
SIGNAL SQLSTATE '01000' SET MESSAGE_TEXT = @ssl_cross_day_msg;
