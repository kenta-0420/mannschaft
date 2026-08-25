-- 既存データの終了時刻が開始時刻より前の行を、日跨ぎ表現へ正規化する。
-- NULL の時刻（終日営業時間・終日block）は対象外とし、既存の終日 semantics を保持する。

UPDATE reservation_slots
SET end_date = DATE_ADD(slot_date, INTERVAL 1 DAY)
WHERE start_time IS NOT NULL
  AND end_time IS NOT NULL
  AND end_time < start_time
  AND (end_date IS NULL OR end_date = slot_date);

UPDATE reservation_business_hours
SET ends_next_day = TRUE
WHERE open_time IS NOT NULL
  AND close_time IS NOT NULL
  AND close_time < open_time
  AND (ends_next_day = FALSE OR ends_next_day IS NULL);

UPDATE reservation_slot_templates
SET ends_next_day = TRUE
WHERE start_time IS NOT NULL
  AND end_time IS NOT NULL
  AND end_time < start_time
  AND (ends_next_day = FALSE OR ends_next_day IS NULL);

UPDATE reservation_blocked_times
SET ends_next_day = TRUE
WHERE start_time IS NOT NULL
  AND end_time IS NOT NULL
  AND end_time < start_time
  AND (ends_next_day = FALSE OR ends_next_day IS NULL);

UPDATE reservation_recurring_blocked_times
SET ends_next_day = TRUE
WHERE start_time IS NOT NULL
  AND end_time IS NOT NULL
  AND end_time < start_time
  AND (ends_next_day = FALSE OR ends_next_day IS NULL);
