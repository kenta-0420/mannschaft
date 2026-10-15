-- F09.16 S5-A: safety_checks テーブルに source_type カラムを追加する。
-- MANUAL: 通常の手動安否確認（F03.6 既存フロー）
-- ORG_WIDE: 管理組合一斉安否確認（F09.16 居住実態管理からの連携）
ALTER TABLE safety_checks
    ADD COLUMN source_type VARCHAR(20) NULL AFTER status;
