-- schedules テーブルに venue_id を追加（既存のlocationカラムとの併用で段階移行）
ALTER TABLE schedules
    ADD COLUMN venue_id BIGINT UNSIGNED NULL COMMENT '施設マスタID（NULLの場合はlocationテキストを使用）' AFTER location,
    ADD INDEX idx_sch_venue (venue_id),
    ADD CONSTRAINT fk_sch_venue FOREIGN KEY (venue_id) REFERENCES venues (id);

-- activity_results テーブルに venue_id と location を追加
ALTER TABLE activity_results
    ADD COLUMN location VARCHAR(300) NULL COMMENT '活動場所（自由入力）' AFTER activity_time_end,
    ADD COLUMN venue_id BIGINT UNSIGNED NULL COMMENT '施設マスタID' AFTER location,
    ADD INDEX idx_ar_venue (venue_id),
    ADD CONSTRAINT fk_ar_venue FOREIGN KEY (venue_id) REFERENCES venues (id);
