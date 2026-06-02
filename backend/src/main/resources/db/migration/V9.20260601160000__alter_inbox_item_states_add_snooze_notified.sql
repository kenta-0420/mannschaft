-- =============================================================
-- F04.11 Phase3 ②: スヌーズ復帰 push 再通知
--   inbox_item_states に「復帰 push 送信済み時刻」を追加する。
--   設計書: docs/features/F04.11_notification_inbox/03_business_logic.md §5
--           docs/features/F04.11_notification_inbox/01_data_model.md §2.1
--
--   snoozed_until 到来時、横断バッチ（InboxSnoozeRevivalBatchService）が
--   未通知の項目を拾って push（WebSocket＋Web Push）を 1 度だけ送り、
--   その時刻を snooze_notified_at に刻む。再スヌーズ（snoozed_until 更新）時は
--   NULL に戻し、再度の復帰通知を許可する（InboxTriageService.snooze）。
-- =============================================================

ALTER TABLE inbox_item_states
    ADD COLUMN snooze_notified_at DATETIME(6) NULL
        COMMENT 'スヌーズ復帰push送信済み時刻。NULL=未送信（再スヌーズ時はNULLへリセット）'
        AFTER snoozed_until;

-- 横断バッチ用: 復帰期限到来かつ未通知かつ非アーカイブの行を効率良く拾う。
-- (snoozed_until, snooze_notified_at) の複合で範囲＋NULL 判定をカバーする。
CREATE INDEX idx_iis_snooze_revival
    ON inbox_item_states (snoozed_until, snooze_notified_at);
