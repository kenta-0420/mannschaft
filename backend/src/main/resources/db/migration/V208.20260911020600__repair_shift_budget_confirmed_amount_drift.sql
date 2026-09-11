-- CMP-260910-1556: シフト予算 月次締めの部分適用によって生じた confirmed_amount のズレを是正する。
--
-- 背景:
--   MonthlyShiftBudgetCloseService#closeOneAllocation は @Transactional(REQUIRES_NEW) を
--   宣言していたが、同一 Bean 内の自己呼び出しで Spring の AOP プロキシを経由しておらず、
--   トランザクションが一切張られていなかった。その結果
--     1. consumptionRepository.save(c) が Spring Data 既定の @Transactional で単独コミット
--        （= 消化レコードは CONFIRMED になる）
--     2. 直後の @Modifying クエリ incrementConfirmedAmount が
--        InvalidDataAccessApiUsageException で失敗
--   となり、巻き戻らない部分適用が残った（消化は CONFIRMED、allocation.confirmed_amount は 0）。
--
-- 是正方針:
--   confirmed_amount を書き換えるのは incrementConfirmedAmount / decrementConfirmedAmount の
--   2 本だけであり、その意味は「生存 CONFIRMED 消化の合計」に他ならない。
--   したがって消化レコードを正本として再計算すれば、ズレの有無に関わらず正しい値に収束する
--   （冪等。既に正しい allocation は差分ゼロで更新対象にならない）。
--   差分のある行だけを更新し、何件直したかがバイナリログで追えるようにする。

UPDATE shift_budget_allocations a
JOIN (
    SELECT c.allocation_id AS allocation_id,
           COALESCE(SUM(c.amount), 0) AS confirmed_total
    FROM shift_budget_consumptions c
    WHERE c.status = 'CONFIRMED'
      AND c.deleted_at IS NULL
    GROUP BY c.allocation_id
) t ON t.allocation_id = a.id
SET a.confirmed_amount = t.confirmed_total,
    a.updated_at = a.updated_at
WHERE a.deleted_at IS NULL
  AND a.confirmed_amount <> t.confirmed_total;

-- CONFIRMED 消化が 1 件も無いのに confirmed_amount が残っている allocation も 0 に戻す
-- （上の JOIN では行が作られないため別文で処理する）。
UPDATE shift_budget_allocations a
SET a.confirmed_amount = 0,
    a.updated_at = a.updated_at
WHERE a.deleted_at IS NULL
  AND a.confirmed_amount <> 0
  AND NOT EXISTS (
      SELECT 1
      FROM shift_budget_consumptions c
      WHERE c.allocation_id = a.id
        AND c.status = 'CONFIRMED'
        AND c.deleted_at IS NULL
  );
