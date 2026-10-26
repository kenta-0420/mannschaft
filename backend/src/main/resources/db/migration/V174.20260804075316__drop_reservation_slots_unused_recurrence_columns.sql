-- F03.4.2 §3.3: 予約スロットの休眠足場 3 列と self-FK を撤去する。
--
-- 背景:
--   recurrence_rule / parent_slot_id / is_exception は F03.4 初期 DDL（V3.061）で
--   「将来の繰り返し枠」を見越して置かれたが、展開ロジック・日次バッチ・書き込み経路の
--   いずれも実装されないまま残置された（recurrence_rule は保存のみ・入力側は既に撤去済み、
--   parent_slot_id は常に NULL、is_exception は常に DDL 既定値 FALSE）。
--   繰り返し枠は reservation_slot_templates（週間テンプレート＋日次バッチ生成）が正であり、
--   この 3 列を流用しない方針が F03.4.2 §3.3 で確定している。
--
-- 削除順序:
--   parent_slot_id には self-FK（fk_reservation_slots_parent, ON DELETE RESTRICT）が張られており、
--   FK を残したまま列を DROP すると ALTER が失敗する。必ず FK → 列 の順で落とす。
--   （V3.061 の INDEX 一覧に idx_reservation_slots_parent は無く、FK が自動生成した
--    インデックスは FK 削除に伴い不要になるため個別の DROP INDEX は行わない。）

ALTER TABLE reservation_slots
    DROP FOREIGN KEY fk_reservation_slots_parent;

ALTER TABLE reservation_slots
    DROP COLUMN recurrence_rule,
    DROP COLUMN parent_slot_id,
    DROP COLUMN is_exception;
