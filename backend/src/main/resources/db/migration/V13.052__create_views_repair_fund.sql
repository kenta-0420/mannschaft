-- F08.8 Phase 1: 修繕積立金関連の読み取り専用ビュー
-- F08.6 (budget_*) を一切改変せず、SELECT 経由で集計データを提供する。

-- v_repair_fund_balance: 修繕積立金の現在残高
CREATE OR REPLACE VIEW v_repair_fund_balance AS
SELECT
  bt.scope_type,
  bt.scope_id,
  SUM(CASE WHEN bt.transaction_type = 'INCOME' THEN bt.amount ELSE -bt.amount END) AS balance,
  MAX(bt.transaction_date) AS as_of_date
FROM budget_transactions bt
INNER JOIN budget_categories bc ON bt.category_id = bc.id
WHERE bt.deleted_at IS NULL
  AND bt.approval_status = 'APPROVED'
  AND bc.name LIKE '%修繕積立金%'
GROUP BY bt.scope_type, bt.scope_id;

-- v_repair_fund_yearly_summary: 年度別収支サマリ
CREATE OR REPLACE VIEW v_repair_fund_yearly_summary AS
SELECT
  bt.scope_type,
  bt.scope_id,
  bfy.id AS fiscal_year_id,
  bfy.name AS fiscal_year_name,
  SUM(CASE WHEN bt.transaction_type = 'INCOME' THEN bt.amount ELSE 0 END) AS total_income,
  SUM(CASE WHEN bt.transaction_type = 'EXPENSE' THEN bt.amount ELSE 0 END) AS total_expense
FROM budget_transactions bt
INNER JOIN budget_fiscal_years bfy ON bt.fiscal_year_id = bfy.id
WHERE bt.deleted_at IS NULL AND bt.approval_status = 'APPROVED'
GROUP BY bt.scope_type, bt.scope_id, bfy.id, bfy.name;

-- v_repair_plan_summary: F09.14 重説書からの参照用
CREATE OR REPLACE VIEW v_repair_plan_summary AS
SELECT
  rpi.scope_type,
  rpi.scope_id,
  rpi.planned_year,
  rpi.category,
  COUNT(*) AS planned_count,
  SUM(rpi.estimated_amount) AS total_estimated
FROM repair_plan_items rpi
WHERE rpi.deleted_at IS NULL AND rpi.status IN ('PLANNED','IN_PROGRESS','DEFERRED')
GROUP BY rpi.scope_type, rpi.scope_id, rpi.planned_year, rpi.category;
