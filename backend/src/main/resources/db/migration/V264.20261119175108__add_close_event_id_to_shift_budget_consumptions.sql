-- CMP-260909-2152 α: 消化行に締めイベントの印 close_event_id を追加する（Prepare で印付け・Abort で外す）。
ALTER TABLE shift_budget_consumptions ADD COLUMN close_event_id BINARY(16) NULL;
