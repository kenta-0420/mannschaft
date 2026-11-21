-- CMP-260909-2152 α: 失敗イベントの種別に MONTHLY_CLOSE（締めの失敗）を加えるため、種別の CHECK 制約を外す（再作成は次のファイル）。
ALTER TABLE shift_budget_failed_events DROP CONSTRAINT chk_sbfe_event_type;
