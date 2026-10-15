-- F03.4+ 臨時休業: 部分時間帯休業対応
-- start_time / end_time が両方 NULL なら従来通り終日休業、
-- 両方セットされている場合は指定時間帯のみの休業として扱う。
-- 時間単位での運用（HH:00:00）を想定し、アプリ層で分=0 をバリデーションする。
ALTER TABLE emergency_closures
    ADD COLUMN start_time TIME NULL COMMENT '部分時間帯休業の開始時刻。NULLなら終日休業' AFTER end_date,
    ADD COLUMN end_time   TIME NULL COMMENT '部分時間帯休業の終了時刻。NULLなら終日休業' AFTER start_time;
