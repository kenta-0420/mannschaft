# AC-18 SQL測定範囲の試練

CI run 38049654507 / head 39d830 の CREATE_SLOT は AUTHZ_BASELINE assertion で失敗した。
原因は未確定。全JVM共有SQLカウンタへ別thread SQLが混入し得るため、要求の認可SQLだけを測る受け入れ条件を試練にする。

## 対象と受け入れ条件

既存 ShiftScheduleSlotFacadeRaceAndQueryIT に1caseのみ追加する。既存6write/read/race caseは注入falseのまま。
新caseは通常のCREATE_SLOTの最外ACS wrapperで、start取得後に専用executorの別thread・実MySQL SELECT 1を一度完了待ちし、実ACSを呼び、endを取得する。
認可baseline同数、HTTP201、認可前scope読取1/FOR UPDATE0、認可後親読取1/FOR UPDATE1という既存assertionを共通経路で保持する。
Future.getの上限10秒は完了待ちの失敗境界でありsleepではない。SQL実行失敗・timeoutも失敗にする。認可・DB・業務Beanを新たにmockしない。
SQL本文を出すprintは新caseで呼ばない。既存caseの動作は保持する。現状の addFilters=false は変更せず、本caseをSecurity filter/E2Eの証明にはしない。

## REDの意味と修正案

現global計測では別threadのSQLを認可区間へ含めるため、semantic assertionのREDを予想する。実REDは未実行。
これは計測器の脆弱性の証明であり、自然CI失敗の原因立証ではない。
実RED採用後、既存global API・複数instance共有契約とAC-17/19跨thread捕捉を維持し、明示的要求thread capture scopeを追加する案。
共有listのinspect/reset/snapshot/集計は同一lockで保護する。一律ThreadLocal化・期待数変更・SQL除外文字列・skip・retryは行わない。
自然実行でforeignSqlPresentとrequestOnlyBaselineEqualを観測できるまではCI根本原因確定としない。

## 実行

root専用canonical枠と隔離Testcontainers MySQLで新caseのみ先行実行する。
--tests 'com.mannschaft.app.shift.ShiftScheduleSlotFacadeRaceAndQueryIT.別スレッドSQLを認可クエリ数に含めない' -Pmax.parallel.forks=1
実行担当はroot。workerはGradle/WSL/services/DBを起動していない。最終回帰は既存6write/read/raceとカウンタ共有契約を含む。
