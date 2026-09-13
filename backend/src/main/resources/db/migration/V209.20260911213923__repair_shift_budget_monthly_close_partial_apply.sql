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
-- 現場で最も普通に起きている姿は「500 を食らった運用者が再実行して 200 を得た」状態である:
--   再実行時には PLANNED が 0 件なので、同じ source に対して
--   「金額 0 の月次仕訳」が正常に保存されてしまう。
--   その結果、消化は CONFIRMED・confirmed_amount は 0・仕訳は 0 円、という
--   金額の食い違いが固定される。「仕訳が無いもの」だけを対象にすると、
--   この一番よくある状態が復旧対象から漏れる。
--
-- したがって検出条件は「仕訳の有無」ではなく「金額の食い違い」で立てる:
--     生存 CONFIRMED 消化の合計  >  生存する月次仕訳の金額合計（無ければ 0）
--   左辺が右辺より大きい = 確定したはずの金額が会計へ届いていない、が部分適用の署名。
--   逆向き（仕訳の方が大きい）は締め後に CONFIRMED 消化が論理削除された場合などに
--   起こりうる別事象であり、消化の差し戻しでは直らないので対象にしない。
--
-- 是正方針（再実行可能な状態へ戻す）:
--   1. 復旧対象を shift_budget_failed_events へ記録する（後述）
--   2. 金額の食い違っている自動記帳の月次仕訳を論理削除する
--   3. 対象 allocation の CONFIRMED 消化を PLANNED へ差し戻す
--   4. confirmed_amount を生存 CONFIRMED 消化の合計から再計算する
--   これで締めを再実行すれば、正しい金額の月次仕訳・監査ログ・confirmed_amount が
--   アプリケーション側の正規の経路で作り直される。SQL で仕訳を再構成する案も検討したが、
--   scope 判定・title 生成・recorded_by のフォールバック・監査ログといった業務ロジックを
--   SQL に二重実装することになり、監査証跡も残らないため採らない。
--
-- なぜ手順 1 で復旧対象を記録するのか:
--   手順 3-4 を実行すると、対象は「PLANNED・confirmed_amount 0・仕訳なし」という
--   未締めと見分けのつかない姿になる。Runbook の差額検出 SQL にも CONFIRMED を条件とする
--   検出にも現れなくなるため、どの組織のどの月を締め直すべきかを控えていない環境では
--   対象を見つける手段が消えてしまう。復旧の第 2 段階（締めの再実行）は手動作業なので、
--   対象を永続化しておかないと仕訳欠損がそのまま残る。
--   専用テーブルを新設せず既存の shift_budget_failed_events を使うのは、
--   このテーブルが「運用者が後から再実行するための失敗キュー」そのものであり、
--   一覧・手動補正済マークの管理 API と Runbook の運用手順が既に存在するため。
--
-- 仕訳を物理削除ではなく論理削除にする理由:
--   BudgetTransactionEntity は @SQLRestriction("deleted_at IS NULL") を持つため、
--   論理削除すれば締めの重複チェック（existsBySourceTypeAndSourceIdAndTransactionDate）
--   からは消え、再締めが通る。会計記録を物理的に消さずに済む。
--
-- 各手順の対象集合について:
--   手順 1-3 はいずれも同じ述語（CONFIRMED 合計 > 生存仕訳合計）で対象を導出する。
--   手順 2 で仕訳を論理削除しても右辺が 0 に下がるだけなので述語は真のままであり、
--   手順 3 で同じ集合が差し戻される。手順 3 の完了後は左辺が 0 になって述語が偽になるため、
--   本 migration は再適用しても何も起きない（冪等）。
--   一時表を使わないのは、接続プールをまたぐと TEMPORARY TABLE が見えなくなるため。

-- 1. 復旧対象を失敗キューへ記録する。
--    event_type は月次締め失敗の既存の記録先に合わせて CONSUMPTION_RECORD を使い、
--    payload.operation で用途を区別する（Runbook 4.2 のとおり本種別は自動リトライされないため、
--    status は最初から EXHAUSTED = 運用者の手当てが必要、として積む）。
INSERT INTO shift_budget_failed_events
    (organization_id, event_type, source_id, payload, error_message, retry_count, status, created_at, updated_at)
SELECT a.organization_id,
       'CONSUMPTION_RECORD',
       a.id,
       JSON_OBJECT('operation', 'MONTHLY_CLOSE_RECOVERY',
                   'allocation_id', a.id,
                   'organization_id', a.organization_id,
                   'period_start', DATE_FORMAT(a.period_start, '%Y-%m-%d'),
                   'period_end', DATE_FORMAT(a.period_end, '%Y-%m-%d'),
                   'year_month', DATE_FORMAT(a.period_start, '%Y-%m'),
                   'confirmed_total', cs.confirmed_total,
                   'journal_total_before', COALESCE(js.journal_total, 0)),
       'CMP-260910-1556 の部分適用を検出し、消化を PLANNED へ差し戻した。月次締めの再実行が必要',
       0,
       'EXHAUSTED',
       NOW(),
       NOW()
  FROM shift_budget_allocations a
  JOIN (SELECT allocation_id, SUM(amount) AS confirmed_total
          FROM shift_budget_consumptions
         WHERE status = 'CONFIRMED' AND deleted_at IS NULL
         GROUP BY allocation_id) cs ON cs.allocation_id = a.id
  LEFT JOIN (SELECT source_id, SUM(amount) AS journal_total
               FROM budget_transactions
              WHERE source_type = 'SHIFT_BUDGET_MONTHLY' AND deleted_at IS NULL
              GROUP BY source_id) js ON js.source_id = a.id
 WHERE a.deleted_at IS NULL
   AND cs.confirmed_total > COALESCE(js.journal_total, 0);

-- 2. 金額の食い違っている自動記帳の月次仕訳を論理削除する（0 円仕訳がここで外れる）。
UPDATE budget_transactions t
  JOIN (SELECT a.id AS allocation_id
          FROM shift_budget_allocations a
          JOIN (SELECT allocation_id, SUM(amount) AS confirmed_total
                  FROM shift_budget_consumptions
                 WHERE status = 'CONFIRMED' AND deleted_at IS NULL
                 GROUP BY allocation_id) cs ON cs.allocation_id = a.id
          LEFT JOIN (SELECT source_id, SUM(amount) AS journal_total
                       FROM budget_transactions
                      WHERE source_type = 'SHIFT_BUDGET_MONTHLY' AND deleted_at IS NULL
                      GROUP BY source_id) js ON js.source_id = a.id
         WHERE a.deleted_at IS NULL
           AND cs.confirmed_total > COALESCE(js.journal_total, 0)) tgt
    ON tgt.allocation_id = t.source_id
   SET t.deleted_at = NOW(),
       t.updated_at = t.updated_at
 WHERE t.source_type = 'SHIFT_BUDGET_MONTHLY'
   AND t.deleted_at IS NULL
   AND t.is_auto_recorded = 1;

-- 3. 対象 allocation の CONFIRMED 消化を PLANNED へ差し戻す。
--    updated_at は明示代入して据え置く（ON UPDATE CURRENT_TIMESTAMP を発火させない）。
UPDATE shift_budget_consumptions c
  JOIN (SELECT a.id AS allocation_id
          FROM shift_budget_allocations a
          JOIN (SELECT allocation_id, SUM(amount) AS confirmed_total
                  FROM shift_budget_consumptions
                 WHERE status = 'CONFIRMED' AND deleted_at IS NULL
                 GROUP BY allocation_id) cs ON cs.allocation_id = a.id
          LEFT JOIN (SELECT source_id, SUM(amount) AS journal_total
                       FROM budget_transactions
                      WHERE source_type = 'SHIFT_BUDGET_MONTHLY' AND deleted_at IS NULL
                      GROUP BY source_id) js ON js.source_id = a.id
         WHERE a.deleted_at IS NULL
           AND cs.confirmed_total > COALESCE(js.journal_total, 0)) tgt
    ON tgt.allocation_id = c.allocation_id
   SET c.status = 'PLANNED',
       c.confirmed_at = NULL,
       c.updated_at = c.updated_at
 WHERE c.status = 'CONFIRMED'
   AND c.deleted_at IS NULL;

-- 4. confirmed_amount を「生存 CONFIRMED 消化の合計」から再計算する。
--    confirmed_amount を書き換える経路は incrementConfirmedAmount /
--    decrementConfirmedAmount の 2 本だけであり、その値の意味はこの合計に等しい。
--    したがってズレの有無に関わらず正しい値に収束する（冪等）。
--    手順 3 で差し戻した allocation はここで 0 に収束する。
UPDATE shift_budget_allocations a
  JOIN (SELECT c.allocation_id AS allocation_id,
               COALESCE(SUM(c.amount), 0) AS confirmed_total
          FROM shift_budget_consumptions c
         WHERE c.status = 'CONFIRMED'
           AND c.deleted_at IS NULL
         GROUP BY c.allocation_id) t ON t.allocation_id = a.id
   SET a.confirmed_amount = t.confirmed_total,
       a.updated_at = a.updated_at
 WHERE a.deleted_at IS NULL
   AND a.confirmed_amount <> t.confirmed_total;

-- 5. CONFIRMED 消化が 1 件も無いのに confirmed_amount が残っている allocation も 0 に戻す
--    （手順 4 の JOIN では行が作られないため別文で処理する）。
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
