-- CMP-260910-1556: シフト予算 月次締めの部分適用で壊れたデータを、再実行可能な状態へ戻す。
--
-- 背景:
--   MonthlyShiftBudgetCloseService#closeOneAllocation は @Transactional(REQUIRES_NEW) を
--   宣言していたが、同一 Bean 内の自己呼び出しで Spring の AOP プロキシを経由しておらず、
--   トランザクションが一切張られていなかった。そのため処理は次の順で中断した。
--     1. consumptionRepository.save(c) が Spring Data 既定の @Transactional で単独コミット
--        （= 消化レコードだけが CONFIRMED になる）
--     2. 直後の @Modifying クエリ incrementConfirmedAmount が
--        InvalidDataAccessApiUsageException で失敗
--     3. その先にある budget_transactions への月次仕訳 INSERT は一度も実行されない
--
--   つまり壊れたデータには「消化は CONFIRMED」「confirmed_amount は 0」
--   「月次仕訳が存在しない」の 3 つが同時に成立している。
--
-- なぜ confirmed_amount の再計算だけでは不足か:
--   会計の本体は budget_transactions の月次仕訳であり、confirmed_amount はその写しに過ぎない。
--   confirmed_amount だけ埋めても仕訳欠損は残る。しかも消化が CONFIRMED のままだと
--   締めを再実行しても PLANNED が 0 件なので、金額 0 の仕訳が作られて終わり、
--   実際の確定額は会計側へ永久に反映されない。
--
-- 是正方針（再実行可能な状態へ戻す）:
--   月次仕訳が存在しない allocation の CONFIRMED 消化を PLANNED へ差し戻す。
--   これで締めを再実行すれば、正しい金額の月次仕訳・監査ログ・confirmed_amount が
--   アプリケーション側の正規の経路で作り直される。SQL で仕訳を再構成する案も検討したが、
--   scope 判定・title 生成・recorded_by のフォールバック・監査ログといった業務ロジックを
--   SQL に二重実装することになり、監査証跡も残らないため採らない。
--
--   ShiftBudgetConsumptionEntity#confirm() の呼び出し元は月次締めただ 1 箇所なので、
--   「CONFIRMED なのに月次仕訳が無い」は部分適用の署名として一意に効く。
--
--   本 migration を適用したあとは、運用者が対象組織に対して月次締めを再実行すること
--   （手順は docs/operations/f087_monthly_close_recovery.md 5.5 を参照）。

-- 1. 月次仕訳が作られていない allocation の CONFIRMED 消化を PLANNED へ差し戻す。
--    updated_at は明示代入して据え置く（ON UPDATE CURRENT_TIMESTAMP を発火させない）。
UPDATE shift_budget_consumptions c
JOIN shift_budget_allocations a ON a.id = c.allocation_id
SET c.status = 'PLANNED',
    c.confirmed_at = NULL,
    c.updated_at = c.updated_at
WHERE c.status = 'CONFIRMED'
  AND c.deleted_at IS NULL
  AND NOT EXISTS (
      SELECT 1
      FROM budget_transactions t
      WHERE t.source_type = 'SHIFT_BUDGET_MONTHLY'
        AND t.source_id = a.id
        AND t.deleted_at IS NULL
  );

-- 2. confirmed_amount を「生存 CONFIRMED 消化の合計」から再計算する。
--    confirmed_amount を書き換える経路は incrementConfirmedAmount /
--    decrementConfirmedAmount の 2 本だけであり、その値の意味はこの合計に等しい。
--    したがってズレの有無に関わらず正しい値に収束する（冪等）。
--    手順 1 で差し戻した allocation はここで 0 に収束する。
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

-- 3. CONFIRMED 消化が 1 件も無いのに confirmed_amount が残っている allocation も 0 に戻す
--    （手順 2 の JOIN では行が作られないため別文で処理する）。
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
