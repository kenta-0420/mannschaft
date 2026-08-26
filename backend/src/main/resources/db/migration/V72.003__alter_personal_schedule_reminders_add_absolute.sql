-- 機能55 第一陣 — 個人スケジュールリマインダーに絶対日時指定を追加。
--
-- 既存 personal_schedule_reminders は相対指定（remind_before_minutes NOT NULL）のみ対応していた。
-- 絶対日時（ABSOLUTE）指定を追加するため reminder_kind を導入し、
-- 絶対指定時は remind_before_minutes が未使用となるため nullable 化する。
ALTER TABLE personal_schedule_reminders
    ADD COLUMN remind_at                 DATETIME    NULL                          COMMENT '絶対日時（ABSOLUTE時に使用）',
    ADD COLUMN reminder_kind             VARCHAR(10) NOT NULL DEFAULT 'RELATIVE'   COMMENT 'RELATIVE / ABSOLUTE',
    MODIFY COLUMN remind_before_minutes  INT UNSIGNED NULL                         COMMENT '相対指定：開始N分前（RELATIVE時に使用。ABSOLUTE時は未使用のため NULL 可）';
