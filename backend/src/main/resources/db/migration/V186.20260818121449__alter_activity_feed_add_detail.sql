-- F03.18: activity_feed に detail 列を追加（スケジュール変更フィードの差分表示用）
ALTER TABLE activity_feed
  ADD COLUMN detail JSON NULL COMMENT 'F03.18 変更差分（SCHEDULE系のみ非NULL）' AFTER summary;
