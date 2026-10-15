-- チーム業務タイムゾーンと日跨ぎ予約枠を追加する。
-- 既存データは従来の Asia/Tokyo / 同日枠として安全に backfill する。

ALTER TABLE teams
    ADD COLUMN timezone VARCHAR(64) NULL;
UPDATE teams SET timezone = 'Asia/Tokyo' WHERE timezone IS NULL OR TRIM(timezone) = '';
ALTER TABLE teams
    MODIFY COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Tokyo';

ALTER TABLE reservation_slots
    ADD COLUMN end_date DATE NULL;
UPDATE reservation_slots SET end_date = slot_date WHERE end_date IS NULL;
ALTER TABLE reservation_slots
    MODIFY COLUMN end_date DATE NOT NULL;
CREATE INDEX idx_reservation_slots_team_end_date ON reservation_slots (team_id, end_date);

ALTER TABLE reservation_business_hours
    ADD COLUMN ends_next_day BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE reservation_slot_templates
    ADD COLUMN ends_next_day BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE reservation_blocked_times
    ADD COLUMN ends_next_day BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE reservation_recurring_blocked_times
    ADD COLUMN ends_next_day BOOLEAN NOT NULL DEFAULT FALSE;
