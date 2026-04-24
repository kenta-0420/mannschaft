-- F13.1 Phase 13.1.2: job_contracts にチェックイン／アウト時刻と業務時間（分）を追加
-- 設計書 §5.2 参照。checked_in_at / checked_out_at は QR スキャン確定時刻を保持し、
-- work_duration_minutes は CHECKED_OUT 遷移時に自動計算される（分未満切り捨て）。
--
-- 備考: V13.003 で定義済みの status ENUM には既に
--   'MATCHED','CHECKED_IN','IN_PROGRESS','CHECKED_OUT','TIME_CONFIRMED',
--   'COMPLETION_REPORTED','COMPLETED','AUTHORIZED','CAPTURED','PAID','CANCELLED','DISPUTED'
-- が全て収録済みであるため、本マイグレーションでの ENUM 拡張は不要。
ALTER TABLE job_contracts
  ADD COLUMN checked_in_at TIMESTAMP(3) NULL AFTER matched_at,
  ADD COLUMN checked_out_at TIMESTAMP(3) NULL AFTER checked_in_at,
  ADD COLUMN work_duration_minutes INT NULL AFTER checked_out_at;
