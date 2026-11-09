# CMP-260910-1811 実行・源泉・終了処理の証拠

このレポートは原XML・system-out・生ログを公開せず、確認済み識別子・判定・digest・件数だけを記録する。実装PRの最終review・CI確認・mergeは後述し、完了台帳は別docs PRで反映する。

## actual-ca87

- 原XMLの実行source HEAD: `ca87ccd4cc801f71c8a166d1ac76de290439067e`（LOCAL_CANONICAL_LINUX）。
- ca87時点の歴史的Backend CI metadata: [run 42602195572](https://github.com/kenta-0420/mannschaft/actions/runs/42602195572)。CI metadataは独立証拠であり、以下のXMLをCI artifactと対応付けない。
- 全19 leaf・74 caseをexact照合。72件は必須非skip成功、2件は源泉付き既存恒久Disabledにより未実施。除外0・未知skip0・欠落0・重複0・failure0・error0。全74成功とは呼ばない。
- 原証跡54ファイルをhash/size照合。source/config16件は実行前後と固定期待hashが一致。ArchUnit37成功、freeze整合性2成功。
- Gradle/child/evidence終了はいずれも0。canonical外側は参照検知によるcleanup留保で1となり、後述の別bridgeで終了処理を完了した。

## actual-cases

| JVM class | Source method | XML testcase name | Result |
|---|---|---|---|
| `com.mannschaft.app.config.TestProfileKeyCoverageGuardTest` | `入れ子の機能フラグ欠落を検出する` | `入れ子の機能フラグ欠落を検出する()` | PASS |
| `com.mannschaft.app.config.TestProfileKeyCoverageGuardTest` | `同じ親の別キーを省略免除しない` | `同じ親の別キーを省略免除しない()` | PASS |
| `com.mannschaft.app.config.TestProfileKeyCoverageGuardTest` | `booleanと数値とnullのleafキーも落とさない` | `booleanと数値とnullのleafキーも落とさない()` | PASS |
| `com.mannschaft.app.config.TestProfileKeyCoverageGuardTest` | `mainのテストプロファイルのキーはtest側にも存在する` | `mainのテストプロファイルのキーはtest側にも存在する()` | PASS |
| `com.mannschaft.app.config.TestProfileKeyCoverageGuardTest` | `値の相違とtest専用キーは欠落とみなさない` | `値の相違とtest専用キーは欠落とみなさない()` | PASS |
| `com.mannschaft.app.config.TestProfileKeyCoverageGuardTest` | `配列要素のleafキー欠落も検出する` | `配列要素のleafキー欠落も検出する()` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetMultiParentIT` | `照合バッチは計上時の組織を使う` | `AC-G125 照合バッチの監査ログ組織は消化行の割当から引いた X` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetMultiParentIT` | `取消は計上時の組織で行う` | `AC-G125 アーカイブ取消は再解決せず、計上時の割当の組織 X で取り消し・監査記録する（Y のフラグ OFF でも）` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetMultiParentIT` | `TODOはどちらの親の予算にも紐づけられる` | `AC-N11 T の TODO を、ACTIVE 加盟している X・Y どちらの予算にも紐づけられる` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetMultiParentIT` | `加盟していない組織の予算には紐づけられない` | `AC-N11 認可境界: 加盟していない組織 Z の予算には T の TODO を紐づけられない（404）` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetMultiParentIT` | `脱退後でも計上時の組織で取り消す` | `AC-G125 T が X を脱退した後でも、取消は計上時の X で行われる` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetMultiParentIT` | `組織権限の無いユーザーは割当を作れない` | `認可境界 組織メンバーでないチーム管理者は、X の割当を作れない（BUDGET_ADMIN 要求）` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetMultiParentIT` | `他組織の割当は読めない` | `認可境界 組織 X の割当は、組織 Y を指定しても読めない（404）` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetMultiParentIT` | `別組織の重なる割当は409` | `AC-N10 X が T に割当を持つ期間に、Y が重なる期間の割当を作ると 409（逐次）` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetMultiParentIT` | `並行作成で一方だけが成功する` | `AC-N15 X と Y が T に期間の重なる割当を同時に作ると、ちょうど一方だけが成功し他方は 409` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetMultiParentIT` | `逆算は明示した組織のフラグで判定される` | `AC-N05 逆算 API は指定した組織のフラグで判定される（X=有効なら通り、Y=無効なら 503 相当）` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetMultiParentIT` | `公開の消化は割当を持つ組織に計上される` | `AC-N10 X だけが T に割当を持つとき、シフト公開の消化は X の割当に計上される（Y のフラグ OFF でも）` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetMultiParentIT` | `加盟していない組織を指定した逆算は404` | `AC-N05 認可境界: チームが ACTIVE 加盟していない組織を指定した逆算は 404（存在オラクルを作らない）` | PASS |
| `com.mannschaft.app.shiftbudget.MonthlyShiftBudgetCloseTransactionIT` | `失敗時に部分適用が残らない` | `AC-2 仕訳 INSERT が失敗したら消化の CONFIRMED 化も確定額の加算も巻き戻る` | PASS |
| `com.mannschaft.app.shiftbudget.MonthlyShiftBudgetCloseTransactionIT` | `月次締めは初回から成功する` | `AC-1 月次締めは初回の呼び出しで成功し、confirmed_amount が加算される` | PASS |
| `com.mannschaft.app.shiftbudget.MonthlyShiftBudgetCloseTransactionIT` | `仕訳未作成のまま壊れたデータを復旧できる` | `AC-3 仕訳が作られないまま止まった allocation は、回復 migration 適用後に締め直すと復旧する` | PASS |
| `com.mannschaft.app.shiftbudget.MonthlyShiftBudgetCloseTransactionIT` | `ゼロ円仕訳ができた状態から復旧できる` | `AC-3 500 のあと再実行して 0 円仕訳ができた状態（現場で最も普通の姿）も復旧できる` | PASS |
| `com.mannschaft.app.shiftbudget.MonthlyShiftBudgetCloseTransactionIT` | `二回目は冪等にskipされる` | `AC-1 2 回目の呼び出しは ALREADY_CLOSED として skip し、確定額を二重加算しない` | PASS |
| `com.mannschaft.app.shiftbudget.MonthlyShiftBudgetCloseTransactionIT` | `復旧対象は失敗キューに記録される` | `AC-3 復旧対象は失敗キューへ記録され、締め直すべき対象を後から辿れる` | PASS |
| `com.mannschaft.app.shiftbudget.MonthlyShiftBudgetCloseTransactionIT` | `同時締めで二重計上されない` | `並行実行 同一 allocation を 2 スレッドが同時に締めても二重計上されない` | PASS |
| `com.mannschaft.app.shiftbudget.MonthlyShiftBudgetCloseTransactionIT` | `正常に締め済みの割当は回復migrationの対象外` | `AC-3 正常に締め済みの allocation は回復 migration で巻き戻されない` | PASS |
| `com.mannschaft.app.shiftbudget.MonthlyShiftBudgetCloseTransactionIT` | `巻き戻った割当は再実行で締められる` | `AC-2 巻き戻った allocation は、原因を取り除けばそのまま締め直せる` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionCancelRaceIT` | `減算途中失敗時は消化更新も全件巻き戻る` | `AC09/12 2件目の実SQL減算が失敗したら、先行分を含め消化と割当が同時rollbackする` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionCancelRaceIT` | `整合バッチと取消リスナーが競合しても二重減算しない` | `AC01/12 batchとarchive listenerが同じ2消化を読んでも各1回だけ減算する` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionCancelRaceIT` | `逐次重複取消と取消対象外は無作用` | `AC08 逐次再実行は0件、CONFIRMED・CANCELLED・削除済み・対象0件は不変` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionReconcileBatchIT` | `健全な消化には触れない` | `AC-10 シフトが PUBLISHED の健全な PLANNED 消化は 1 件も取り消さない` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionReconcileBatchIT` | `孤児消化を取り消し再実行は0件` | `AC-09 ARCHIVED シフトに残った孤児 PLANNED 消化を取り消し、再実行では 0 件（冪等）` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionReconcileBatchIT` | `論理削除シフトの孤児消化も取り消される` | `AC-09 論理削除済みシフトに残った孤児 PLANNED 消化も取り消される` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetThresholdAlertNotificationBoundaryIT` | `一部受信者の失敗で残りが巻き添えにならない` | `途中失敗: 1人目の配送が実DBで落ちても、残りの受信者へ配送が続き、落ちた受信者は再試行台帳に残る` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetThresholdAlertNotificationBoundaryIT` | `業務がロールバックしたら通知も出ない` | `因果: イベントを publish した業務トランザクションがロールバックしたら通知は1件も出ない` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftBudgetThresholdAlertNotificationBoundaryIT` | `業務がコミットされれば通知は届く` | `対照: イベントがコミットされれば閾値超過警告は実際に届く` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftManualArchiveConsumptionCancelIT` | `公開取消でも消化が残らない` | `AC-13 PUBLISHED から ADJUSTING への後戻り（公開取消）でも消化が残らない` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftManualArchiveConsumptionCancelIT` | `ロールバックしたら消化は取り消されない` | `境界: 業務トランザクションがロールバックしたら消化は取り消されない` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftManualArchiveConsumptionCancelIT` | `アーカイブしてから削除しても二重減算しない` | `AC-08 アーカイブ→削除でも consumed_amount が二重減算されない` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftManualArchiveConsumptionCancelIT` | `手動アーカイブ後は割当を削除できる` | `AC-04 手動アーカイブ後は allocation を削除できる（SHIFT_BUDGET_012 で恒久ブロックされない）` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftManualArchiveConsumptionCancelIT` | `手動アーカイブで消化が取り消される` | `AC-01 手動アーカイブのコミット後、PLANNED 消化が CANCELLED になり consumed_amount が減算される` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftManualArchiveConsumptionCancelIT` | `手動アーカイブの監査ログに操作者が載る` | `AC-11 消化取消の監査ログに操作者IDが載る（バッチは null、手動は操作者）` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftManualArchiveConsumptionCancelIT` | `削除してからアーカイブしても二重減算しない` | `AC-08 削除→アーカイブでも consumed_amount が二重減算されない（削除済みは遷移不可のまま）` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftManualArchiveConsumptionCancelIT` | `手動アーカイブで変更依頼が取り下げられる` | `AC-03 手動アーカイブで OPEN の変更依頼が WITHDRAWN 化される（バッチ経路と副作用が一致する）` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftManualArchiveConsumptionCancelIT` | `論理削除で消化が取り消される` | `AC-07 deleteSchedule（論理削除）でも PLANNED 消化が CANCELLED になる` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftManualArchiveConsumptionCancelIT` | `再アーカイブは冪等` | `AC-05 既に ARCHIVED のシフトを再度 ARCHIVED にしても二重減算しない` | PASS |
| `com.mannschaft.app.shiftbudget.ShiftManualArchiveConsumptionCancelIT` | `手動アーカイブでTodoが取り消される` | `AC-02 手動アーカイブのコミット後、紐づく自動作成 Todo が CANCELLED になる` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest` | `保存_全フィールドが永続化される` | `保存_全フィールドが永続化される` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$FindByIdAndOrganization` | `自組織のIDは取得できる` | `自組織のIDは取得できる` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$FindByIdAndOrganization` | `別組織のIDは見えない` | `別組織のIDは見えない（IDOR対策）` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$FindByIdAndOrganization` | `論理削除済みは見えない` | `論理削除済みは見えない` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$FindContainingPeriod` | `当該月のallocationを返す` | `当該月のallocationを返す` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$FindContainingPeriod` | `範囲外は空` | `範囲外（前月）は空` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$FindContainingPeriod` | `組織全体割当も解決できる` | `teamId が NULL の組織全体割当も解決できる` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$ConsumedAmountAtomic` | `アトミック減算_減算が反映される` | `アトミック減算_減算が反映される` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$ConsumedAmountAtomic` | `アトミック増減_加算が反映される` | `アトミック増減_加算が反映される` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$UniqueConstraint` | `論理削除後の同一スコープINSERT_可能` | `論理削除後の同一スコープINSERT_可能（deleted_at 含有 UNIQUE の確認）` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$UniqueConstraint` | `同一スコープ重複INSERT_例外` | `同一スコープ重複INSERT_例外` | SOURCE_DISABLED_NOT_EXECUTED |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$ListByOrganization` | `組織配下を期間降順で返す` | `組織配下を期間降順で返す（論理削除済みを除外）` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest` | `保存_全フィールドが永続化される` | `保存_全フィールドが永続化される` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest$FindBySlotUserStatus` | `CANCELLEDはPLANNED検索でヒットしない` | `CANCELLED 状態は PLANNED 検索でヒットしない` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest$FindBySlotUserStatus` | `PLANNED検索で取得できる` | `PLANNED検索で取得できる` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest$ExistsByAllocationAndStatuses` | `PLANNED残存_true` | `PLANNED残存_true` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest$ExistsByAllocationAndStatuses` | `CANCELLEDのみ_false` | `CANCELLEDのみ_false` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest$ExistsByAllocationAndStatuses` | `該当allocationに消化なし_false` | `該当 allocation に消化なし_false` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest$FindByShift` | `シフト紐付検索_該当シフト配下を全件返す` | `シフト紐付検索_該当シフト配下を全件返す` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest$UniqueConstraint` | `CANCELLED遷移後はPLANNED再INSERT可能` | `CANCELLED_遷移後は同一slot_userで新規PLANNEDを再INSERT可能（§11.1）` | PASS |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest$UniqueConstraint` | `同一slot_user_status重複INSERT_例外` | `同一slot_user_status重複INSERT_例外` | SOURCE_DISABLED_NOT_EXECUTED |
| `com.mannschaft.app.common.transaction.SelfInvocationRequiresNewTransactionIT` | `ストレージ移行は失敗時に部分適用を残さない` | `AC-2 移行単位の後段で落ちたらそのファイルのキー更新は巻き戻り、他ファイルの移行は残る` | PASS |
| `com.mannschaft.app.common.transaction.SelfInvocationRequiresNewTransactionIT` | `再マッチバッチはトランザクション内で走る` | `AC-1 再マッチバッチはチャンク単位のトランザクション内で走り、provider_id を確定する` | PASS |
| `com.mannschaft.app.common.transaction.SelfInvocationRequiresNewTransactionIT` | `課金ブリッジは失敗時に部分適用を残さない` | `AC-2 キャンペーン処理の後段で落ちたら、その月の請求明細も請求書も消費予算も残らない` | PASS |
| `com.mannschaft.app.common.transaction.SelfInvocationRequiresNewTransactionIT` | `再マッチバッチは失敗時に部分適用を残さない` | `AC-2 チャンクの後段で落ちたら、同じチャンクで書き換えた provider_id は全て巻き戻る` | PASS |
| `com.mannschaft.app.common.transaction.SelfInvocationRequiresNewTransactionIT` | `課金ブリッジはトランザクション内で走る` | `AC-1 課金ブリッジはキャンペーン単位のトランザクション内で走り、請求書合計まで確定する` | PASS |
| `com.mannschaft.app.common.transaction.SelfInvocationRequiresNewTransactionIT` | `ストレージ移行はトランザクション内で走る` | `AC-1 ストレージパス移行は 1 ファイル単位のトランザクション内で走り、キーを確定する` | PASS |

## source-links

| JVM class | Fixed source |
|---|---|
| `com.mannschaft.app.config.TestProfileKeyCoverageGuardTest` | [source](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/config/TestProfileKeyCoverageGuardTest.java) |
| `com.mannschaft.app.shiftbudget.ShiftBudgetMultiParentIT` | [source](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/ShiftBudgetMultiParentIT.java) |
| `com.mannschaft.app.shiftbudget.MonthlyShiftBudgetCloseTransactionIT` | [source](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/MonthlyShiftBudgetCloseTransactionIT.java) |
| `com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionCancelRaceIT` | [source](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/ShiftBudgetConsumptionCancelRaceIT.java) |
| `com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionReconcileBatchIT` | [source](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/ShiftBudgetConsumptionReconcileBatchIT.java) |
| `com.mannschaft.app.shiftbudget.ShiftBudgetThresholdAlertNotificationBoundaryIT` | [source](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/ShiftBudgetThresholdAlertNotificationBoundaryIT.java) |
| `com.mannschaft.app.shiftbudget.ShiftManualArchiveConsumptionCancelIT` | [source](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/ShiftManualArchiveConsumptionCancelIT.java) |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest` | [source](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/repository/ShiftBudgetAllocationRepositoryTest.java) |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$FindByIdAndOrganization` | [source](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/repository/ShiftBudgetAllocationRepositoryTest.java) |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$FindContainingPeriod` | [source](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/repository/ShiftBudgetAllocationRepositoryTest.java) |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$ConsumedAmountAtomic` | [source](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/repository/ShiftBudgetAllocationRepositoryTest.java) |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$UniqueConstraint` | [source](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/repository/ShiftBudgetAllocationRepositoryTest.java) |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$ListByOrganization` | [source](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/repository/ShiftBudgetAllocationRepositoryTest.java) |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest` | [source](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/repository/ShiftBudgetConsumptionRepositoryTest.java) |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest$FindBySlotUserStatus` | [source](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/repository/ShiftBudgetConsumptionRepositoryTest.java) |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest$ExistsByAllocationAndStatuses` | [source](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/repository/ShiftBudgetConsumptionRepositoryTest.java) |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest$FindByShift` | [source](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/repository/ShiftBudgetConsumptionRepositoryTest.java) |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest$UniqueConstraint` | [source](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/repository/ShiftBudgetConsumptionRepositoryTest.java) |
| `com.mannschaft.app.common.transaction.SelfInvocationRequiresNewTransactionIT` | [source](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/common/transaction/SelfInvocationRequiresNewTransactionIT.java) |

## existing-disabled

2件はbase mainと実行sourceで同じ宣言が存在する既存恒久Disabled。フラグ起動条件不足の代用扱いや解除はしていない。

| JVM class / method | Annotation source | Source Git blob | Source SHA256 |
|---|---|---|---|
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$UniqueConstraint#同一スコープ重複INSERT_例外` | [@Disabled](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/repository/ShiftBudgetAllocationRepositoryTest.java#L257) | `84bed245aa920817fd93224cdb9a8d5db1b53ccc` | `f94c75660b6809860e99522c2f951e05590d426dcb84cc614191fbab8333c1be` |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest$UniqueConstraint#同一slot_user_status重複INSERT_例外` | [@Disabled](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/repository/ShiftBudgetConsumptionRepositoryTest.java#L215) | `3dfa086d20b30b203e76a412788b9702b2e10abc` | `9e2bc22884c5d4e12ab91e45055874a72f2c78f82f5bdffe4f012dbc252bf203` |

## original-xml-digests

原19XMLは私有保全し、内容は公開しない。

| JVM class | Original XML bytes | SHA256 |
|---|---:|---|
| `com.mannschaft.app.config.TestProfileKeyCoverageGuardTest` | 1312 | `35dd1f8aef5c1c2637a34348be064fefe1d9ef824c07dc38a9ce39bec991f5ab` |
| `com.mannschaft.app.shiftbudget.ShiftBudgetMultiParentIT` | 3471 | `bad8b567907ec1e55518984e8bfe6da5c2776bd54709093015f43d39dd0334b4` |
| `com.mannschaft.app.shiftbudget.MonthlyShiftBudgetCloseTransactionIT` | 4368 | `03d07e4d0dc5e481036afbef0bcd0dc8dba768515805dd0ba0a3c7df5513e89c` |
| `com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionCancelRaceIT` | 3808 | `93e719efaab882a262b91267687bd98dbd67357b6d897fd0a4430f05b0a0be6d` |
| `com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionReconcileBatchIT` | 4158 | `c5440dbda7017f413e3df4d6f55524182f6ba4ef19cb81db060f64dd9763fa4c` |
| `com.mannschaft.app.shiftbudget.ShiftBudgetThresholdAlertNotificationBoundaryIT` | 17147 | `3d1a09e62626b07b4545fe974c13440cdddb9a073f591e1f0e5482a6a57efd17` |
| `com.mannschaft.app.shiftbudget.ShiftManualArchiveConsumptionCancelIT` | 2784 | `66f6ad26334848580cba0a30614d76921f5f61e1f693e59e69607370a9d3c709` |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest` | 509 | `982adb4791e23ae4d70c0dc0b36e8b81a7b365264709593ddcb9de73b1b6a61c` |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$FindByIdAndOrganization` | 917 | `067f8057da79d7813ca13131ea5811e1cbb595882841b12952c8953279dc3283` |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$FindContainingPeriod` | 919 | `6e60fca8bf4fe547a48a72ae09c58cb804ec41ab701dd91c4eb4870814de7f7e` |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$ConsumedAmountAtomic` | 755 | `d327ecb06a06494c778bd20a9befc240cc1c926469c76cbcf4ed344f94572fc1` |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$UniqueConstraint` | 771 | `2bafbbe82166c977c51ef30bdd39ad9b959d569cf7863c396ef1f156fc1dd272` |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepositoryTest$ListByOrganization` | 581 | `fdf37502af77c69804f15156764f77f3695b7b51cf6eb9040d61a802fcd0b4dd` |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest` | 511 | `4552fe40abb3684d21bcfa835af2b5a3fe304d0fa71f44becdde85fc750edf97` |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest$FindBySlotUserStatus` | 752 | `c4d8bb639572cb6d975390b34fdab44eb481e3bbd0167b58d2aed585f0d4d879` |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest$ExistsByAllocationAndStatuses` | 913 | `bb4a09645a1abdc749838fdeceefd218f9a0db078ff3e9b9f383bc70040b66b8` |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest$FindByShift` | 565 | `5f28751de67285dec727fe418b5ebb8e944676acb7c5f7ae0f04ac68df44a37c` |
| `com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepositoryTest$UniqueConstraint` | 764 | `8910341c01fabda464655f1ee043b3c7da5dbbd0fd52316b4adfad4043ae2d0a` |
| `com.mannschaft.app.common.transaction.SelfInvocationRequiresNewTransactionIT` | 228961 | `010955ec8ec8431006da98b04480b3706444a053ae129c1742eb7588000c67bf` |

## source16-digests

以下は実行sourceの相対repository pathと生bytes SHA256。前後変更0。

| Repository path | SHA256 |
|---|---|
| [backend/src/test/java/com/mannschaft/app/config/TestProfileKeyCoverageGuardTest.java](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/config/TestProfileKeyCoverageGuardTest.java) | `fed946b91dafa3a1654a1a1b066a49ea443d22ef5771d974aeb6fd6ad04677cc` |
| [backend/src/test/java/com/mannschaft/app/shiftbudget/ShiftBudgetMultiParentIT.java](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/ShiftBudgetMultiParentIT.java) | `6d8352d9155d55d7a63b028c1a0f3053dc0aceca3c4aa6abd840747cca7e0a56` |
| [backend/src/test/java/com/mannschaft/app/shiftbudget/MonthlyShiftBudgetCloseTransactionIT.java](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/MonthlyShiftBudgetCloseTransactionIT.java) | `25cfe86e851c876af6b161ad973d806b5b05ca2377c0650e4ba0965ed7c4e7f7` |
| [backend/src/test/java/com/mannschaft/app/shiftbudget/ShiftBudgetConsumptionCancelRaceIT.java](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/ShiftBudgetConsumptionCancelRaceIT.java) | `bfdbd384b7649bc9fdb262967daabe3a85e2dd411212f1ce80a96712d76a0bcb` |
| [backend/src/test/java/com/mannschaft/app/shiftbudget/ShiftBudgetConsumptionReconcileBatchIT.java](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/ShiftBudgetConsumptionReconcileBatchIT.java) | `1d7e5d2efc243a994ae0518c1a57737a5ad092e68f88fd64663b4abe172e850d` |
| [backend/src/test/java/com/mannschaft/app/shiftbudget/ShiftBudgetThresholdAlertNotificationBoundaryIT.java](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/ShiftBudgetThresholdAlertNotificationBoundaryIT.java) | `599e23756c5593392694b771ca6fde9ef7a9d20bb5e016d2565f2fdf7a7d570a` |
| [backend/src/test/java/com/mannschaft/app/shiftbudget/ShiftManualArchiveConsumptionCancelIT.java](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/ShiftManualArchiveConsumptionCancelIT.java) | `0ae3f97a3d0348326a98fd52a44721f4b44184d0ac01fed5c5a9043dfeebf3b3` |
| [backend/src/test/java/com/mannschaft/app/shiftbudget/repository/ShiftBudgetAllocationRepositoryTest.java](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/repository/ShiftBudgetAllocationRepositoryTest.java) | `f94c75660b6809860e99522c2f951e05590d426dcb84cc614191fbab8333c1be` |
| [backend/src/test/java/com/mannschaft/app/shiftbudget/repository/ShiftBudgetConsumptionRepositoryTest.java](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/shiftbudget/repository/ShiftBudgetConsumptionRepositoryTest.java) | `9e2bc22884c5d4e12ab91e45055874a72f2c78f82f5bdffe4f012dbc252bf203` |
| [backend/src/test/java/com/mannschaft/app/common/transaction/SelfInvocationRequiresNewTransactionIT.java](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/java/com/mannschaft/app/common/transaction/SelfInvocationRequiresNewTransactionIT.java) | `aaf3ad6de9bf3756a8a06d36aa8ef2dec392fdf4d45a6024d4e82adefe208623` |
| [backend/src/test/resources/application-test.yml](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/src/test/resources/application-test.yml) | `1a1dbf4a5e2e81ed5084f11fad18c2b50c2e77cc2b1923fa6553e5d160a08fd7` |
| [backend/build.gradle.kts](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/build.gradle.kts) | `ae60285678e25420b9481bc786d4c23e000ac44347007d3146043318f7a60523` |
| [backend/build-logic/src/main/kotlin/mannschaft.backend-conventions.gradle.kts](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/build-logic/src/main/kotlin/mannschaft.backend-conventions.gradle.kts) | `43672c557a910e919a7a185900a66d2b42a32d410470d52d584f9f7a52f3d42e` |
| [backend/build-logic/src/main/kotlin/ShardAssignment.kt](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/build-logic/src/main/kotlin/ShardAssignment.kt) | `1116f2af1f17a8ac6ff32d6f8ee153fe6fc1aa507c4d9d3849255d96d201a2b1` |
| [backend/gradle.properties](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/gradle.properties) | `b86f6e524ac50fb413fe5f2c3c483f159a9e538f6a7a3b9c381811167bb16b67` |
| [backend/scripts/gradle-turnstile.sh](https://github.com/kenta-0420/mannschaft/blob/ca87ccd4cc801f71c8a166d1ac76de290439067e/backend/scripts/gradle-turnstile.sh) | `02d5ebdb49ec0285372a7a7dac64fb646e765f56a5e59c10af02303cfc08db7c` |

## auxiliary-results

- ArchUnit: 37 PASS、failure/error/skip 0。freeze整合性: 2 PASS、failure/error/skip 0。
- JaCoCo CSV: データ7257行＋header1行（計7258レコード）、639902 bytes、SHA256 `0f770f94039ff46cbc6c45037789c8e9f12fe62d7f9f136d65bbaec0f3721610`。原CSV自体はこの公開候補へ複製しない。
- scratch内freeze差分: 5ファイル、253行削除・追加0、232021 bytes、SHA256 `7144a13fe1b494003d3a2698a3cdd29bad43cb118d062401cf33c9ce2a10b3f9`。本陣source freeze変更・baseline返済を意味しない。

## formal-red

- 固定RED source HEAD: `c3d4232adb77071463598ca8a64bdcbf5e0ae7fa`。
- 原guard XML: 6件＝5 controls PASS＋同期欠落を検出する意図したfailure 1、error/skip 0。XML SHA256 `05036981aa5f94af57c8025684679c84b3d2a1ca827f7bf6e3b9714abd2caaa9`。
- 原自然Gradle終端: exit 1、失敗task `:test`。原native終端receipt SHA256 `1c07c31d75b8af58058fcc78c57bd5af5ed932158e2ed0054065cce5f48231d9`。
- root RED採用記録 SHA256 `836411a8078bd5af6c7a97ae4bbe1adf26e2d607fc21e1fdcdbabf5e985ea436`、自然終端追補 SHA256 `198dc6ba7a48a631ee0cbc0c46fe69cb1c4a4b3fc87b71dbf83585c5538491ac`。
- RED source: [guard](https://github.com/kenta-0420/mannschaft/blob/c3d4232adb77071463598ca8a64bdcbf5e0ae7fa/backend/src/test/java/com/mannschaft/app/config/TestProfileKeyCoverageGuardTest.java)。

| RED XML testcase name | Result |
|---|---|
| `入れ子の機能フラグ欠落を検出する()` | CONTROL_PASS |
| `同じ親の別キーを省略免除しない()` | CONTROL_PASS |
| `booleanと数値とnullのleafキーも落とさない()` | CONTROL_PASS |
| `mainのテストプロファイルのキーはtest側にも存在する()` | INTENDED_MISSING_8_FAILURE |
| `値の相違とtest専用キーは欠落とみなさない()` | CONTROL_PASS |
| `配列要素のleafキー欠落も検出する()` | CONTROL_PASS |

検出した欠落8キー（値は記載しない）:

- `feature.shift-budget.enabled`
- `feature.shift-budget.monthly-close-cron-enabled`
- `feature.shift-budget.retry-batch-enabled`
- `mannschaft.error-monitoring.aggregation.enabled`
- `mannschaft.error-monitoring.health-polling.enabled`
- `mannschaft.line.signature-verify.mode`
- `mannschaft.security.internal-signing-key`
- `pointcard.rematch-batch.enabled`

## cleanup-and-adoption

| Execution | Source HEAD | Cleanup bridge SHA256 | Original receipt SHA256 |
|---|---|---|---|
| 旧521実走 | `521848ff375402b02750851d6a189c0625db2f8b` | `568b77c512544cfd3d48817ec99516ffd3474dd4c1a1b086d4ee139ff47ed34c` | `d896013be25f9a93382872397fafed2edc231cf1598ca67d6c0e89b43f1162d5` |
| ca87実走 | `ca87ccd4cc801f71c8a166d1ac76de290439067e` | `f6f6210bbcd3357dc32982daa0f67cc76178195573c7c0e26dc2a87d59e0f4bf` | `c2a8984229bdc971c531630e58cccef209cfb18cf19ca919dd3dc45d9cfa940c` |

両cleanupともremoved・対象不存在・削除数1・signals0・原receipt不変を確認。ca87は直前の元Map読み取り監査refs0と所有者照合を経た。

- root原raw独立レビュー SHA256 `29ff26d6c7d2ef5b192f7e09a89426206fbb6f27ecf8c5aaf41e399a5b01b7d4`。
- root runtime/lifecycle採用記録 SHA256 `5446d6a1060d32f6bf513ad46d864283364255010f263e846dd8e3d04e1f5844`。ca87の72必須非skip成功＋既存Disabled2未実施とlifecycle完了を採用。このruntime採用記録時点では出荷許可・実装mergeはfalse。その後の最終確認と実装mergeは後述する。
- 原classification・原receiptはその時点の記録として不変保持し、cleanupは別bridgeで結合する。

## implementation-source-bridge

- 実装PR: [#4431](https://github.com/kenta-0420/mannschaft/pull/4431)。実装head 24a93902ea8c9a3c0063e3eab1bbb8e8d2bffde9。
- 原ローカル実行headは ca87ccd4cc801f71c8a166d1ac76de290439067e。実装head24aのローカル再実行を行ったという記録ではない。
- main追従後もbackend tree OIDは 000976a9cc34433aac27f06e284dc713de605542 で同一。canonical入力16件と追加制御8件はGit blob・生bytes SHA256を比較し全件不変。
- source同一bridge記録SHA256: ac6bf56591954384fcb5eb2237e7bdcc003947798bfc290c14156dd46a975b3e。従ってca87実走の源泉を保持したまま、24a実装headとの同一性を結ぶ。

| Additional canonical control | Git blob | SHA256 |
|---|---|---|
| [scripts/verification-run.mjs](https://github.com/kenta-0420/mannschaft/blob/24a93902ea8c9a3c0063e3eab1bbb8e8d2bffde9/scripts/verification-run.mjs) | `5bc46ead316802ad40e1429c0036404796f3a0a8` | `f1f78972710ecf9fb7092ec0da388d4c9ab33645ad263e3f435f7425ae897f7f` |
| [backend/gradlew](https://github.com/kenta-0420/mannschaft/blob/24a93902ea8c9a3c0063e3eab1bbb8e8d2bffde9/backend/gradlew) | `b5955f089916209e68b2d6dc5e1ed23d4607d642` | `7301e006d31c9bed1c522ef4dba1e6050786ead469b5e9df0daf2c77e197a737` |
| [backend/gradlew.bat](https://github.com/kenta-0420/mannschaft/blob/24a93902ea8c9a3c0063e3eab1bbb8e8d2bffde9/backend/gradlew.bat) | `db3a6ac207e507b0bc1635a9f2c18d3b174e682e` | `94102713eb8fb22d032397924c0f38ab2da783ba60d07054339f1190a0c4e2cd` |
| [backend/gradle/wrapper/gradle-wrapper.properties](https://github.com/kenta-0420/mannschaft/blob/24a93902ea8c9a3c0063e3eab1bbb8e8d2bffde9/backend/gradle/wrapper/gradle-wrapper.properties) | `aaaabb3cb9fe5724e930d5a7f25f84afb29cfb37` | `aef287d114ce3153c3d535697a61928f5034990d570cbb2e93df549e9671483d` |
| [backend/gradle/wrapper/gradle-wrapper.jar](https://github.com/kenta-0420/mannschaft/blob/24a93902ea8c9a3c0063e3eab1bbb8e8d2bffde9/backend/gradle/wrapper/gradle-wrapper.jar) | `1b33c55baabb587c669f562ae36f953de2481846` | `7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172` |
| [backend/settings.gradle.kts](https://github.com/kenta-0420/mannschaft/blob/24a93902ea8c9a3c0063e3eab1bbb8e8d2bffde9/backend/settings.gradle.kts) | `d1730505ac09a3d0abbe765b093963b3766923a9` | `46facc9025ce605102cfc94b7962e1e572221b655bb47b894af44e5a8f92ced0` |
| [backend/build-logic/build.gradle.kts](https://github.com/kenta-0420/mannschaft/blob/24a93902ea8c9a3c0063e3eab1bbb8e8d2bffde9/backend/build-logic/build.gradle.kts) | `e4a377cb50c8aa6c871ce15883c32c5230b43f7e` | `28bea01e9b249544f4f3c146a9c657a6fce311e6ac8f4d917cb51c499512c88c` |
| [backend/build-logic/settings.gradle.kts](https://github.com/kenta-0420/mannschaft/blob/24a93902ea8c9a3c0063e3eab1bbb8e8d2bffde9/backend/build-logic/settings.gradle.kts) | `db3c9fccc0bd7511ba8cf637e1b6e337447f63d7` | `e7462385eae6d6b5c805fffaf10124e92f6df3753fb33b36ba7e2560a4c2541e` |

## implementation-ci-and-merge

- 実装head24aの正式Backend CI: [run 42630842297](https://github.com/kenta-0420/mannschaft/actions/runs/42630842297)。全6 shard・aggregate成功。全7 workflow成功。
- マージ直前のroot API確認では、同headの最新check setは26件（success25・週次history skip1）、未完了・failure・cancelled 0。
- 実際の必須4 checkはCLIでpassを確認（記録SHA256 1230fbbc455a91c38275666e4d5ba7d7dce9e2fa952efac633ca5b8e7d3378a3）。

| Required check | Confirmed result | Public reference |
|---|---|---|
| Task-List PR Gate | SUCCESS | [check](https://github.com/kenta-0420/mannschaft/actions/runs/42633208240/job/129214548762) |
| Compile & Test | SUCCESS | [check](https://github.com/kenta-0420/mannschaft/actions/runs/42630842297/job/129210537045) |
| ER Diagram Drift Check | SUCCESS | [check](https://github.com/kenta-0420/mannschaft/actions/runs/42630842254/job/129206419885) |
| OpenAPI Drift Check | SUCCESS | [check](https://github.com/kenta-0420/mannschaft/actions/runs/42630842134/job/129206419543) |

履歴: body更新・Ready時の旧Task-List run 42633192560 attempt1にはcancelledがあった。対象jobを1件だけ再実行し、[attempt2](https://github.com/kenta-0420/mannschaft/actions/runs/42633192560/attempts/2) は2026-11-09 14:26:45 UTCにsuccess。元取消は履歴として保持し、rootが最新setのcancelled0をAPIで確認してからmergeした。

- 実装PRはAPIでMERGEDを確認。2026-11-09 14:28:05 UTCに通常squash merge、main/squash commitは [87b8804040a67a8a0c46ac08e339407edee660cd](https://github.com/kenta-0420/mannschaft/commit/87b8804040a67a8a0c46ac08e339407edee660cd)。
- schema1完了証拠のimplementation SHAは実装PR head24a、ci配列はそのheadのrun 42630842297のみ。ca87の旧CIは歴史参照に留め、現CIへ付け替えない。

## final-review-and-scope

rootはAC3件の実装・試験対応、finding0、source同一bridge、72必須非skip成功＋源泉付き既存Disabled2未実施、原54file・source16・補助検査・終了処理を独立確認し、実装merge前の最終reviewをpassedとした。

変更scopeはtest-only。実Spring・実MySQLの試験設定と番人・棚卸文書の是正であり、製品API・UI・本番設定・利用者導線の変更を含まないため、ブラウザー実機と住民探索はnot-applicable。CI/ITの結果を実機操作や住民探索の実施へ読み替えない。
