-- 機能55 第一陣 — 出欠リマインダーに相対指定（開始N分前）を追加。
--
-- 既存 schedule_attendance_reminders は絶対日時（remind_at NOT NULL）のみ対応していた。
-- 親予定の開始時刻基準の相対指定（RELATIVE）を追加するため、reminder_kind を導入し、
-- 相対指定時は remind_at が未確定となるため nullable 化する。
ALTER TABLE schedule_attendance_reminders
    ADD COLUMN remind_before_minutes INT         NULL                          COMMENT '相対指定：開始N分前（RELATIVE時に使用）',
    ADD COLUMN reminder_kind         VARCHAR(10) NOT NULL DEFAULT 'ABSOLUTE'   COMMENT 'RELATIVE / ABSOLUTE',
    MODIFY COLUMN remind_at          DATETIME    NULL                          COMMENT '絶対日時（ABSOLUTE時に使用。RELATIVE時は未確定のため NULL 可）';
