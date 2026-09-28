# CMP-019 Wave 16 実機・アリシゼーション（2026-09-28）

## 現HEADの更新証跡（571f0d867e8a、2026-09-28 13:23 JST）

この節は、以下に残る13:11以前のCI・実機スナップショットを更新する。Wave 16は引き続き作業中であり、実機E2E未合格、3住民アリシゼーション未実施である。

- **CI**: 現HEAD `571f0d867e8a3e6bfb0b3cfc78893fc4f97a3ff1` のrun [36370706237](https://github.com/kenta-0420/mannschaft/actions/runs/36370706237) は全6 shardとrequired checksが成功。JUnit artifactでは `ConfirmableNotificationScopeContractIT` が89 tests / skipped 0 / failures 0 / errors 0、`RecruitmentNoShowConfirmPenaltyIT` が2 / 0 / 0 / 0、`RecruitmentScopeContractIT$LiftPenalty` が7 / 0 / 0 / 0。
- 新mapper回帰testcase `永続化コンテキスト終了後も本人の未確認通知を表示名付きで変換できる` は1件実行・skip 0・failure 0・error 0。XMLはartifact `test-results-xml-pr-3499-shard-5` 内 `TEST-com.mannschaft.app.notification.confirmable.ConfirmableNotificationScopeContractIT.xml`。
- **Java 25 runtime**: 13:23:31 JST時点でreadyから1591秒経過。health `200 UP`。login / profile / notification list API は各HTTP 200。これらはバッチ実機E2Eの合格を意味しない。
- **root UI確認・RED**: 合成fixtureのCN 67 / app notification 3286060 / user 23、recipient 123、pending=falseを確認。確認前は確認button 1件。タイトルを開くとread APIが200となり、確認後はbutton 0件・「確認済み」label 1件。rootの最終DB queryでは未確認0件。screenshotは `test-results/wave16-read-confirm-before.png` と `test-results/wave16-read-confirm-after.png`（root確認済み）。これは合成UI確認で、実ペナルティのE2Eではない。
- 最初にUI fixtureが表示されなかったのは、通知が初期ページ外だったため。1時間以降に本人通知が1148件ある状態から、所有者が特定した対象通知1件だけcreatedAtを更新して再現した。ユーザー通知の一括削除はしていない。
- **未解決・未完了**: Java 21 launcherのAPPCRASH原因は未解明。実機E2Eの2 specは未合格、本人・他account・第三者の3住民アリシゼーションは未実施。synthetic UIで確認済み表示の挙動を確認しただけで、実DB上の実ペナルティ適用・通知経路を合格扱いにしない。

- 対象: PR #3499 / Wave 16。無断キャンセル確定と緊急確認通知。
- 現状: **作業中。実機E2Eは未合格、3アカウントのアリシゼーションは未実施。**
- 修正済みHEAD: `571f0d867e8a3e6bfb0b3cfc78893fc4f97a3ff1`（push済み）。
- 実機用ビルド: `compileTestJava` と `bootJar` は成功。ただし `-x test` で実行しており、JUnitテスト成功の証拠ではない。

## 最新CI（2026-09-28 13:23 JST snapshot）

- Backend: 現HEAD `571f0d867e8a3e6bfb0b3cfc78893fc4f97a3ff1` の [run 36370706237](https://github.com/kenta-0420/mannschaft/actions/runs/36370706237) は全6 shard成功。追加の `Compile & Test` aggregate check も成功。
- OpenAPI: [run 36370706200](https://github.com/kenta-0420/mannschaft/actions/runs/36370706200) 成功。Non-JST、Shard coverage、Smoke E2E、Lighthouse、Installとrequired checksも成功。
- 今回HEADのJUnit artifactで `ConfirmableNotificationScopeContractIT` 89 tests、`RecruitmentNoShowConfirmPenaltyIT` 2 tests、`RecruitmentScopeContractIT$LiftPenalty` 7 testsを確認。3 suiteとも skipped 0 / failures 0 / errors 0。新mapper回帰caseのXML・件数は冒頭の更新証跡を参照。
- main push限定Docker build検証と週次フル履歴スキャンは対象外のため skip。skipを成功テスト数には含めない。

## Issue #3498 受け入れ条件トレーサビリティ（コード照合、実行結果は上記のとおり未確定）

| AC類型 | 実装 | 単体・実MySQL IT | 実UI・アリシゼーション |
| --- | --- | --- | --- |
| 1. 確定NO_SHOWからの対象・スコープ判定 | `RecruitmentNoShowConfirmBatch` が24時間経過レコードを確定し `RecruitmentPenaltyService#evaluateAndApplyPenalty` を呼ぶ。設定のTEAM/ORGANIZATIONと `THIS_SCOPE_ONLY` / `ALL_SCOPES` 集計をServiceで扱い、PERSONALは対象外。 | `RecruitmentPenaltyServiceTest` の設定なし・無効・閾値未満・ALL_SCOPES判定、`RecruitmentNoShowConfirmPenaltyIT` の確定からペナルティ永続化を対象とする。artifact未確認。 | `cmp-019-wave16-no-show-confirm-penalty.real.spec.ts` のTEAM/ORGANIZATION 2本が対象だが未実行。 |
| 2. 新規適用時だけ本人へURGENT確認通知1件 | Serviceが `RecruitmentPenaltyAppliedNotificationEvent` を発行し、既存の確認通知経路が `RECRUITMENT_PENALTY_APPLIED` / URGENT / 本人確認導線を処理する。 | Service unitの新規発行・既存ペナルティ非発行、ITの通知・確認受信者永続化を対象とする。artifact未確認。 | 同specが通知表示・確認操作・再実行時の1件性を確認する設計だが未実行。 |
| 3. 非適用条件・再実行・並行実行の一回性 | 無設定・無効・閾値未満・REVOKED・既存有効ペナルティをService/Repositoryのロック・一意性で抑止する。 | Service unitの非適用条件と既存GLOBAL非発行、実MySQL ITの再実行・一回性を対象とする。artifact未確認。 | 同specの再実行とDB確認が対象だが未実行。 |
| 4. ロールバックとコミット後配送 | 確定・適用・通知作成はトランザクションで扱い、既存通知配送はコミット後に処理する。 | `RecruitmentNoShowConfirmPenaltyIT#rollbackKeepsNoShowUnconfirmedAndCreatesNeitherPenaltyNorNotification` がロールバック不変を対象とする。配送失敗で適用を壊さない既存通知基盤の回帰もCI artifact未確認。 | 実機での配信完了確認は未実行。 |
| 5. TEAM/ORGANIZATION・本人・対象外の権限境界と確認導線 | スコープ付き設定と本人宛確認通知を既存認可・通知自己スコープ経路へ接続する。 | 実MySQL ITの対象スコープ・本人通知を対象とし、通知自己スコープは既存 `NotificationSelfScopeContractIT` が構造的境界を担保する。今回artifact未確認。 | 実BE/FE/DB E2E 2本と、本人・他account・第三者の3住民アリシゼーションは未実行。 |

`確認ボタン／確認済みラベルが既読状態に依存する可能性` は、コード上の疑義だけで実機再現していない。上表の合否・不具合として確定しない。

## 実機E2E

- 修正後の API login と profile は HTTP 200。通知画面も HTTP 200 を返したが、画面は loading のまま。専用BEが停止したため、実機E2Eとしては未合格。
- 旧実機試行では、GLOBAL penalty 1件と本人向け URGENT confirmable notification 1件まで生成された後、`/api/v1/me/confirmable-notifications/pending` が user ID 23 の `LazyInitializationException` で HTTP 500 になった。専用fixtureのcleanupは成功。
- LazyInitializationException の修正を含むHEADのビルドは成功しているが、修正後の実機APIシナリオはまだ完了していない。
- Java 21の `Start-Process` 管理による旧起動では、launcher PID 19132 が12:36:33にAPPCRASHした。health確認とシナリオ完了の記録はない。
- PowerShell の `$ErrorActionPreference = 'Stop'` と `java *> log` の組合せでは、`java -version` の対照試験でも `NativeCommandError` により exit 1 となった。これは旧起動方式の記録であり、アプリ障害の根拠ではない。

## Java runtime障害と比較診断（2026-09-28 12:56 JST時点）

- Java 21（`C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot\bin\java.exe`）は、11:15:12、12:05:46、12:30:21、12:36:33にWindows APPCRASHを記録した。いずれも `c0000005`、fault module `unknown`、同一WER障害bucket `1747307971022174473` / `StackHash_2264` である。空きメモリ16GB、Memory event 2004なしを確認した。
- WER archiveの対象 `Report.wer` はACLにより読取拒否された。`C:\Claude\mannschaft` と `C:\Users\kenta` 配下に既存 `hs_err_pid*.log` はない。現稼働Javaには外部DLL注入の証跡を得られなかったが、クラッシュ済PIDについて断定はできない。
- 比較診断としてのみ、Java 25（`jdk-25.0.2.10-hotspot`）で検証runtimeを起動した。launcher PID 80336、子Java PID 98508、専用ログ・stateは `wave16-runtime-java25*`。Java 21の根治を宣言するものではない。health未確認のため、実機E2E・住民操作はまだ開始していない。

## 合成UI fixture（実ペナルティ試験とは別）

- UI状態確認専用の confirmable notification `CN 67`、app notification `3286060`、user `23` を作成済み。
- run tag: `CMP019-W16-ALIC-1790564446145`。
- このfixtureには実ペナルティがなく、バッチ生成・ペナルティ適用の実機E2E証跡として扱わない。

## 確認状態と既読状態

- 確認ボタン／確認済みラベルが既読状態に依存する可能性をコードから確認した段階。実機での再現・アリシゼーションは未実施で、bug確定とはしていない。

## 3アカウントのアリシゼーション

本人・他account・第三者の3ケースは未実施。実機E2Eと分けて記録する。

| ケース | 状態 | 結果 |
| --- | --- | --- |
| 本人 | 未実施 | 未確認 |
| 他account | 未実施 | 未確認 |
| 第三者 | 未実施 | 未確認 |

## 旧CI結果（現HEADの証拠ではない）

[run 36362722711](https://github.com/kenta-0420/mannschaft/actions/runs/36362722711) は旧HEAD `9284e770bf24bf3391ea391c29ab27e4822b4109` の検証結果。次のJUnit suiteは pass だったが、実機E2Eや3アカウント検証を証明しない。

| JUnit suite | tests | skipped | failures | errors |
| --- | ---: | ---: | ---: | ---: |
| `RecruitmentNoShowConfirmPenaltyIT` | 2 | 0 | 0 | 0 |
| `RecruitmentScopeContractIT$LiftPenalty` | 7 | 0 | 0 | 0 |

## 次の作業

- 新BEのhealthを確認し、修正後HEADで実機E2Eを最後まで再実行する。通知生成、本人確認、再実行時の重複なし、cleanup結果を記録する。
- 実機E2E後に3アカウントのアリシゼーションを実施し、未実施を合格扱いしない。
- 次陣準備: [Issue #3502](https://github.com/kenta-0420/mannschaft/issues/3502)、公開仕様の既存不備: [Issue #3503](https://github.com/kenta-0420/mannschaft/issues/3503)。
- CI、実機、3アカウント検証の各結果がそろうまでは Wave 16 を合格としない。
# 最新状態追記（2026-09-28、Wave16未合格）

- `NotificationServiceTest` は32件、失敗0・エラー0・スキップ0。XMLは `backend/build/wave16-notification-state-green-xml` に保存。FE `NotificationList` は5件全緑。
- 実BEはJava 25 PID 44256 / 8081、FEはNode PID 97040 / 3003で、FE `127.0.0.1:3003` はHTTP 200。
- `.nuxt/wave16-e2e-skip-diagnostic.json` は exit 1。TEAMは156770msでfail（期待404、実際403、outsiderDetail line782）、ORGはserial skip 0ms。追加cleanupエラーなし。
- 古い571 CIのScope89 / NoShow2 / Lift7は旧HEADの証拠。現source Scope91は未CI、OpenAPI boolean|null生成中。実機2件と3住民アリシゼーションは未合格で、完了扱いにしない。

# 最新証拠更新（2026-09-28、Wave16未合格）

- 実BE `127.0.0.1:8081/actuator/health` は HTTP 200、FE `127.0.0.1:3003/` は HTTP 200。
- Backend `NotificationServiceTest` は32件、失敗0・エラー0・スキップ0。Frontend `NotificationList` は5件全緑。生成SDKは実コマンドの終了コード0。
- `isConfirmed` は `boolean | null` の明示スキーマ（`@Schema`）となり、SpringDoc OpenAPI 3.1 の実生成は HTTP 200・8.294875秒、paths 2683・schemas 4139。対象field以外の構造差分は0件。
- 実機再試行は2件とも未合格（TEAM/ORG）。TEAMは156770msで fail（期待404、実際403、`outsiderDetail` line 782）、ORGは serial skip 0件。cleanup は `AggregateError`、ORG serial skip 0件、実際の対象テスト green は0/2。owned data は未確認で原因調査中。
- 現HEAD `571f0d8` の古いCI Scope89 greenは今回変更のCI証拠ではない。最新sourceのCIはpendingで、過去CIをWave16の合格根拠にしない。main HEAD `8fb0691c16`（3 commits ahead）の取り込みもpending。
- 3住民アリシゼーションは未実施（0件）。whole CMP/F03.11 は partial、Issue #3498/#3499 は未完了。残件は Issue #3502/#3503。未実施項目を合格扱いにしない。
- 証拠生成物の存在を確認した: `C:\Claude\mannschaft\.claude\worktrees\cmp019-wave16\backend\build`、`C:\Claude\mannschaft\.claude\worktrees\cmp019-wave16\frontend\.nuxt`。raw report の環境情報は記録しない。

## 最新実機再試行（current HEAD `3681057ed1`、2026-09-28）

- retest 1 は TEAM が 156770ms で fail（期待404／実403）。続く retry は 300019ms で strict 3-match に到達したが、最新 retest 2 は TEAM が timedOut（637633ms、test budget 600000ms）となり、cleanup は `AggregateError`。
- retest 2 の ORG は serial skipped 1件、duration 0ms。actual target は green 0/2。owned data は未確認で原因調査中。
- current HEAD `3681057ed1` の push は成功したが、main `8fb0691c16`（3 commits ahead）との競合により新CIは未起動。確認できる最新runは旧 `571f0d8` のみで、main取り込み待ち。
