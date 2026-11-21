-- CMP-260909-2152 α: 締め期間の範囲検索（割当×状態×勤務日）の索引。
ALTER TABLE shift_budget_consumptions ADD KEY idx_sbc_allocation_status_work_date (allocation_id, status, work_date);
