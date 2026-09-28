# CMP-019 Wave 16 実機・アリシゼーション（2026-09-28）

## 1. 現在状態

- CIで検証した実装HEADは `5ed8f2fee9`。OpenAPI `36425344566`、Frontend `36425344428`、Backend `36425344388`（全6 shardとaggregate）、GeneratedTypes `36425344411`、Smoke `36425344453`、Lighthouse `36425344512` はすべてSUCCESS。
- OpenAPIは正式task終了直前に生成され、raw/current/candidateの構造差0、同runtime Gson 2.10候補とbytes一致・SHA `720C0692...0E61`、5998202 bytes（LFなし）、SDK `generate:types` exit 0・diff 0を確認済み。task overallはproblems-report AccessDeniedでfailedだったが、形式修正後のOpenAPI・Frontend・GeneratedTypes・Smoke・Lighthouse・Backendの新CIはすべてSUCCESS。
- 実BE `127.0.0.1:8081/actuator/health` とFE `127.0.0.1:3003/` のHTTP 200は前回実測時の記録であり、現在の稼働を保証しない。`NotificationServiceTest` 32件は失敗0・エラー0・スキップ0、FE `NotificationList` 5件はgreen。前回DOM診断は17 rows、API HTTP 200。
- 実機 retest2 は TEAM timedOut 637633ms（test budget 600000ms）、cleanup `AggregateError`。ORGはserial skip 1件・duration 0ms。actual target greenは0/2。
- 3住民アリシゼーションは0件。ユーザーは、今回および今後に作成し所有確認できた試験専用データの削除を許可した。cleanup proofは23:17:28Zにread-onlyでexit 0（`0|0|0|0|0|2|0`）。対象listing/participant/penalty setting/confirm setting/active team/notificationsは各0で、join_requestsの履歴2件は保持した。初回helperはmutation/API成功後に証拠SQLの存在しない列でexit 1となったが、証拠SQLのみ修正してexit 0を確認し、mutationは再実行していない。raw reportの環境情報は記録しない。
- FE専用launcherは23:22:59Zに開始したが240秒のreadinessを確認できず、専用所有treeは停止済み。retest3は未開始であり、実機E2Eの再試行結果ではない。
- origin/main `4b2c3438bf694e28256bf2cf67219e30ff0c9b28` との3 BEファイル競合は解消済みである。root reviewでは、mainとの差分が本人限定状態射影追加・全status source exists追加・createのtype条件のみで、両仕様を保持することを確認した。最新main統合commit、統合後CI、新runtimeでの実機検証は待ち。
- Wave16は未合格。未実施・未確認をgreenや完了として扱わない。
- 試験専用データの削除はユーザー許可済みで、cleanup proofも確認済み。retest3はFE readiness待ちかつorigin/main統合中のため未開始であり、実機結果はまだ0/2のままである。

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
- CI実装HEAD `08235466cf` のJUnit artifactは `ConfirmableNotificationScopeContractIT` 26 XML / 91 tests、`NotificationServiceTest` qualified prefix 9 XML / 32 tests、`RecruitmentScopeContractIT$LiftPenalty` 7 tests、`RecruitmentNoShowConfirmPenaltyIT` 2 testsがすべて skip/failure/error 0。その後の `5ed8f2fee9` は全6 CI SUCCESSだが、origin/main `4b2c3438bf` の統合中変更には統合後CIと実機再確認が必要。
- Java 21 launcherのAPPCRASH（`c0000005`）原因は未解明。比較用Java 25 runtimeを使用した記録はあるが、根治やE2E合格を意味しない。

## 4. 残作業

- 解消済みの3 BE競合を最新mainへ統合してcommitし、統合後CIをgreenにする。`5ed8f2fee9` の全6 CI SUCCESSと、統合後の結果を区別して確認する。
- 実機E2EをTEAM/ORGとも完走させ、通知生成・本人確認・再実行一回性・cleanupを実DBで確認する。
- cleanup proofで試験専用データの残存なしを確認済み。retest3後は、今回および今後に作成し所有確認できる試験専用データについて、ユーザー許可の範囲で同じ確認と清掃を行う。
- 実機E2E後に本人・他account・第三者の3住民アリシゼーションを実施する。
- Issue #3502（手動 `RECRUITMENT_PENALTY_LIFTED` 通知TODO）とIssue #3503（F03.11手動解除全体の既存不備）は未完了。#3498/#3499も実機・アリシゼーション完了まで未完了とする。
