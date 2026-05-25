-- event_checkins.ticket_id を「チェックイン種別に応じた条件付き必須」にする DB レベル制約。
--
-- 背景:
--   ticket_id は点呼（ROLL_CALL / ROLL_CALL_BATCH）・代理（PROXY）チェックインのため
--   NULL 許可（V70.006）になった。しかしその副作用として、
--   チケット式（STAFF_SCAN / SELF）なのに ticket_id が無い不正行を
--   DB が防げなくなっていた。
--
-- 対応:
--   アプリ層の保証に加え、DB レベルで
--     「チケット式（STAFF_SCAN / SELF）は ticket_id 必須」
--     「チケットレス（ROLL_CALL / ROLL_CALL_BATCH / PROXY）は ticket_id = NULL」
--   を CHECK 制約で強制する。
--
-- 注:
--   PROXY は F03.10（代理出席）で追加予定の checkin_type（現時点では未実装）。
--   F03.10 実装時に PROXY 行が制約違反にならないよう、前方互換として事前に含める。
ALTER TABLE event_checkins
  ADD CONSTRAINT chk_event_checkins_ticket_by_type CHECK (
    (checkin_type IN ('STAFF_SCAN', 'SELF') AND ticket_id IS NOT NULL)
    OR
    (checkin_type IN ('ROLL_CALL', 'ROLL_CALL_BATCH', 'PROXY') AND ticket_id IS NULL)
  );
