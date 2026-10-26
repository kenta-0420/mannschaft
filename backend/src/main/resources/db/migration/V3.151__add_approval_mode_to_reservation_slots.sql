-- F10.7: reservation_slots に承認モードカラムを追加
-- AUTO: 予約を自動承認、MANUAL: 管理者が手動承認するモード
ALTER TABLE reservation_slots
  ADD COLUMN approval_mode ENUM('AUTO', 'MANUAL') NOT NULL DEFAULT 'AUTO'
  AFTER is_exception;
