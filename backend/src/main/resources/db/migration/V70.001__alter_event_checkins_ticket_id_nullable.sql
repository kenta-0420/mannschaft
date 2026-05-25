-- event_checkins.ticket_id を NULL 許可へ変更する。
--
-- 背景:
--   V3.104 で ticket_id は BIGINT UNSIGNED NOT NULL として定義されていたが、
--   EventCheckinEntity は当初から ticket_id を nullable 前提で扱っており、
--   点呼（CheckinType.ROLL_CALL / ROLL_CALL_BATCH）チェックインは
--   チケットを介さず ticket_id = NULL（rollCallUserId がチケットの代替）で記録する設計である。
--   そのため Flyway 適用済みの本番 DB では点呼チェックインの INSERT が
--   NOT NULL 制約違反で失敗する潜在バグが存在していた。
--
-- 対応:
--   スキーマとドメインモデル（Entity の nullable 前提）を一致させるため、
--   ticket_id を NULL 許可に変更する。
--   既存の UNIQUE KEY uq_event_checkins_ticket と FK fk_event_checkins_ticket は維持する。
--   （MySQL では UNIQUE 制約上の NULL は重複可、NULL 行は FK チェック対象外のため問題なし）
ALTER TABLE event_checkins
  MODIFY COLUMN ticket_id BIGINT UNSIGNED NULL
    COMMENT 'FK → event_tickets.id（点呼などチケットを介さない場合は NULL）';
