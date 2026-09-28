# CMP-019 Wave 16 実機・アリシゼーション（2026-09-28）

## 1. 現在状態

- CIで検証した実装HEADは `08235466cf`。main `8fb0691c16` から `0c819212e0` への後続取り込みはmerge `1dcf405beb` で完了し、BEソース差分はない。後続形式変更とmain追従のCIは再確認待ち。
- OpenAPIは正式task終了直前に生成され、raw/current/candidateの構造差0、同runtime Gson 2.10候補とbytes一致・SHA `720C0692...0E61`、5998202 bytes（LFなし）、SDK `generate:types` exit 0・diff 0を確認済み。task overallはproblems-report AccessDeniedでfailed。形式修正は反映済みだが、新CIは待ち。Frontend run `36413808915`、GeneratedTypes `36413808881`、Smoke `36413809037`、Lighthouse `36413808862` はsuccess。`NotificationList.spec.ts` は5 passed / 176ms（UTC 11:14:50）。Backend run `36413808923` は全6 shardとaggregateがsuccess。
- 実BE `127.0.0.1:8081/actuator/health` とFE `127.0.0.1:3003/` はHTTP 200。`NotificationServiceTest` 32件は失敗0・エラー0・スキップ0、FE `NotificationList` 5件はgreen。前回DOM診断は17 rows、API HTTP 200。
- 実機 retest2 は TEAM timedOut 637633ms（test budget 600000ms）、cleanup `AggregateError`。ORGはserial skip 1件・duration 0ms。actual target greenは0/2。
- 3住民アリシゼーションは0件。Terraのowned範囲（TEAM 1017/1018、listing 90255〜90257、setting 9/10）は照会済みで、削除・soft-deleteは承認待ち。raw reportの環境情報は記録しない。
- Wave16は未合格。未実施・未確認をgreenや完了として扱わない。
- テスト専用旧runデータの物理listing/participant削除とteam soft-deleteは自動承認審査で拒否され、対象範囲は提示済み。ユーザー明示承認待ちのため再テスト3は未実行。

## 2. Issue #3498 受け入れ条件と実証状態

| AC | 実装・単体 | 実機・アリシゼーション | 判定 |
| --- | --- | --- | --- |
| 確定NO_SHOWから対象・スコープを判定 | `RecruitmentPenaltyServiceTest`、`RecruitmentNoShowConfirmPenaltyIT` が対象。NotificationServiceTest 32件はread/confirmed DTOの確認であり、このACの直接証拠ではない。`RecruitmentNoShowConfirmPenaltyIT.xml` はtests 2 / testcases 2 / skipped 0 / failures 0 / errors 0を確認 | TEAM/ORG retest2は完走せず | 未合格 |
| 新規適用時だけ本人へURGENT通知 | `RecruitmentPenaltyServiceTest` の新規適用・通知発行、`RecruitmentNoShowConfirmPenaltyIT` の永続化を対象。NotificationServiceTest 32件とFE 5件は通知状態表示の補助証拠 | 実通知の生成・確認・再実行を完走できず | 未合格 |
| 非適用条件・再実行・並行実行の一回性 | `RecruitmentPenaltyServiceTest` の閾値・無効・既存適用条件、`RecruitmentNoShowConfirmPenaltyIT` の再実行・一回性を対象 | 実DBの再実行結果は未確認 | 未合格 |
| ロールバックとコミット後配送 | `RecruitmentNoShowConfirmPenaltyIT#rollbackKeepsNoShowUnconfirmedAndCreatesNeitherPenaltyNorNotification` を対象。CI実装HEAD `08235466cf` の該当caseはgreen（skips/failures/errors 0） | 実機E2Eは完走せず | 未合格 |
| TEAM/ORG・本人・対象外の認可境界 | qualified prefix `ConfirmableNotificationScopeContractIT` は26 XML / 91 tests、skip/failure/error 0。NotificationServiceTest 32件はDTO状態の補助証拠 | 実機2件0/2 green、3住民0件 | 未合格 |

## 3. 短い時系列履歴

- OpenAPI生成: literal `\n` によりCI parse failure。実改行へ修正し、JSON.parseと `npm run generate:types` exit 0、生成SDK差分なしを確認。GeneratedTypes CIはgreen。
- Frontend Typecheck: `NotificationList.spec.ts` の `createI18n` 6言語型不足とDOMWrapperへの `.exists()` が原因でfailure。6言語messagesと `findAll(...).toHaveLength(1)` へ修正し、対象lint green。再CIは成功。
- 実機 retest1はTEAM 156770msで期待404／実403。retryは300019msでstrict 3-matchに到達。retest2はTEAM timeout 637633ms、cleanup AggregateError、ORG serial skip 1件。
- synthetic REDで、`read=true`・`confirmed=false`なのにUIが「確認済み」表示になる欠陥を実測。過去に確認成功と記録されたものは合成fixtureの誤った判定であり、実ペナルティ通知の成功証拠ではない。
- CI実装HEAD `08235466cf` のJUnit artifactは `ConfirmableNotificationScopeContractIT` 26 XML / 91 tests、`NotificationServiceTest` qualified prefix 9 XML / 32 tests、`RecruitmentScopeContractIT$LiftPenalty` 7 tests、`RecruitmentNoShowConfirmPenaltyIT` 2 testsがすべて skip/failure/error 0。後続形式変更とmain追従のCIは再確認待ち。
- Java 21 launcherのAPPCRASH（`c0000005`）原因は未解明。比較用Java 25 runtimeを使用した記録はあるが、根治やE2E合格を意味しない。

## 4. 残作業

- 後続形式変更とmain追従のCIをgreenにし、CI実装HEAD `08235466cf` の検証結果と区別して確認する。
- 実機E2EをTEAM/ORGとも完走させ、通知生成・本人確認・再実行一回性・cleanupを実DBで確認する。
- 所有範囲を再照会し、承認後にテスト専用データを清掃する。
- 実機E2E後に本人・他account・第三者の3住民アリシゼーションを実施する。
- Issue #3502（手動 `RECRUITMENT_PENALTY_LIFTED` 通知TODO）とIssue #3503（F03.11手動解除全体の既存不備）は未完了。#3498/#3499も実機・アリシゼーション完了まで未完了とする。
