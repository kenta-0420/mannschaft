-- CMP-260909-2152 α: 消化行に勤務日 work_date を追加する（段階移行の第1段。NULL 許容で追加し、後続で埋めて NOT NULL 化する）。
ALTER TABLE shift_budget_consumptions ADD COLUMN work_date DATE NULL AFTER slot_id;
