-- CMP-260909-2152 α: work_date を NOT NULL にする。NULL が残っていれば移行エラー（枠が無い行の勤務日は推測しない。
-- 開発DBは手順書 docs/operations/f087_monthly_close_recovery.md のとおり削除か作り直し）。
ALTER TABLE shift_budget_consumptions MODIFY COLUMN work_date DATE NOT NULL;
