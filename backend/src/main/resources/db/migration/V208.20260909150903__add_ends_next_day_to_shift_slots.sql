-- シフト枠の日跨ぎフラグ（設計 F03.5 §11.2.5 規則5）。
-- 日跨ぎは end_time < start_time の暗黙表現ではなく本フラグで明示する。
-- 既存行はすべて日跨ぎでないため既定 0（後方互換）。
ALTER TABLE shift_slots
    ADD COLUMN ends_next_day BOOLEAN NOT NULL DEFAULT FALSE COMMENT '翌日終了（日跨ぎ）か' AFTER end_time;
