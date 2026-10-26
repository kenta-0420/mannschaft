-- F03.4 予約管理: 予約枠に定員(capacity)を追加しオーバーブッキングを根治する。
--
-- 背景（実機E2Eで発見）:
--   ReservationSlotService.incrementAndCheckFull がメソッド名に反して markFull() を呼ばず、
--   かつ枠に定員の概念が無かったため、同一予約枠へ無制限の人数が予約でき、
--   予約後もグリッド/空き枠一覧が「空き」のままだった（美容院 1:1 指名で同一枠に複数予約が入る事故）。
--
-- 方針（マスター確定）:
--   予約枠ごとに定員(capacity)を設定可能とし、既定は 1（＝1:1 指名）。
--   booked_count >= capacity で枠を FULL 化し、以降その枠は予約不可。
--   キャンセルで booked_count < capacity に戻れば AVAILABLE へ自動復帰する。
--
-- 後方互換:
--   既存の全行は DEFAULT 1 で埋まる（＝従来の 1:1 想定と一致）。NOT NULL 制約を満たす。
ALTER TABLE reservation_slots
    ADD COLUMN capacity INT NOT NULL DEFAULT 1 AFTER booked_count;
