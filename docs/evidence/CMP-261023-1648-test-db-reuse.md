# CMP-261023-1648 テストDB再利用の検証記録

## 対象と結論

- Issue: [#4066 テストDB再利用とFlyway再生重複の削減](https://github.com/kenta-0420/mannschaft/issues/4066)
- CMP: `CMP-261023-1648`。ユーザーの2026-10-23優先指示により、再利用できるテストのDBを再利用し、FlywayによるDB作成を「なるべく早めに」短縮する。
- 実装PR: [#4068](https://github.com/kenta-0420/mannschaft/pull/4068)
- 対象: `backend/src/test/java/com/mannschaft/app/billing/migration/BillingPayerHandoverFoundationFlywayIT.java`
- 検証済みJavaのrevision: `f6ab116b39386f2e7d257afd0cdad4783292dabf`（実装コミット `9cbe0868ba2d61d8e8f453482a5eab2c6327c46a`）

既存12件を保持した14件の実DB検証、fixture隔離のnegative controlと完全復元後のSchema群9件、両群順、並列有効時の逐次実行を確認した。同一専用runnerでの単回比較では、対象限定Gradle command wallが1072.057秒から671.706秒へ約400.350秒（37.34%）短縮した。この値はGradleと必要依存・初期化を含む対象コマンドの測定であり、全テストスイートの短縮率ではない。

Payer改善の実装・性能/隔離検証を保持した上で、追加のcontext共有・既存fixture是正を実装PR #4075でmergeした。最終headの通常CI、専用実測、merge時点のcheck完了を確認し、本記録PRで完了証拠を追加する。旧実装PR #4068の時刻ゲート拒否は後段の履歴として保持する。

## 変更範囲と初期化構造

既存の共有基底クラスを変更せず、新たな共有基底も作らない。対象クラスが所有するMySQLコンテナ1個の起動・停止ライフサイクルを維持する。永続キャッシュ、Gradle設定、製品コード、API、UI、本番SQL、migrationのchecksumは変更しない。一時検証branchのworkflow/init/orchestratorは実装PRへ混ぜない。

限定検索ではMySQLContainerを含む110ファイル中、`BeforeEach` とclean/migrateが共存する候補は対象Payerと `BillingCenterFoundationFlywayIT` の2件だった。後者の2件は `v196FoundationContract` による旧契約維持と `orphanActiveContractPointerStopsBeforeExpand` による孤児pointerの移行途中失敗をそれぞれ検証するため旧schema再生を維持し、今回の共用対象にはしない。

旧版移行の既存5件を `MigrationTests` に配置し、各検証前に `clean → V202 → 旧契約fixture` を実施する。完成schemaの既存7件と隔離回帰2件は `SchemaTests` に配置し、`PER_CLASS` の `BeforeAll` で `clean → V202 → 旧契約fixture → 現行末尾` を1回実施する。各群がcleanから開始するため、他群の残存schemaを信用しない。

完成schema群の `BeforeEach` / `AfterEach` は、所有する固定IDの要求・追加契約だけを子→親順に削除する。旧契約や未知fixtureを一括削除しない。無関係fixtureを検証する番兵も検証自身の専用固定IDであり、専用finallyで削除する。後始末例外は元の検証失敗へsuppressedとして保持する。外側の `Execution(SAME_THREAD)` で同じschemaを使う検証を逐次実行する。

全履歴の初期化12回→6回、Flyway `migrate()` 呼び出し24回→12回は、呼び出し構造と対象14件の実行確認による根拠である。実行ログから回数を直接数えた結果ではない。`target(null)` による現行末尾への実Flyway適用は、V206の `FAILING_CLEANUP` と生成列UNIQUEの契約を検証するため維持する。

## 受け入れ条件と証拠

| 条件 | 対応する実装・テスト | 確認結果 |
| --- | --- | --- |
| AC-1 既存12検証を保持し、完成schemaは1回初期化、2件目以降にclean/migrateしない | 旧版5件、完成schema既存7件。`initializeCompletedSchema` に集約 | 通常PRと専用検証で14件成功。baseline12件の表示名集合を保持し、不足0・回帰2件追加。回数削減は上記の構造根拠 |
| AC-2 所有fixtureだけをcleanupし、空・単独・逆順・途中失敗でも汚染せず未知fixtureを保持 | `完成schemaのcleanupは所有fixtureだけを削除する` と `完成schemaのcleanupは途中失敗後も再利用できる` | 回帰2件green、所有要求DELETEだけ無効化したnegative controlで2件が意図した理由で失敗。完全復元後Schema9件を単独実行してgreen。空/反復cleanup・所有行削除・無関係行保持・途中INSERT失敗後の再要求を確認 |
| AC-3 初期migration失敗はJUnit失敗とし、握りつぶしや初期化済み扱いにしない | 初期化処理にmigrate例外のcatchや初期化済みflagなし。`backfillGuardFailsMigrationWhenNoFallbackIsResolvable` を保持 | 実MySQLで解決不能旧データのFlywayException拒否がpass。初期化のcatchなしという静的確認と合わせて評価。BeforeAll故障注入は未実施であり、その実験を実施済みとは扱わない |
| AC-4 同schemaは逐次、両群順で初期化が自立、worker間はコンテナ隔離 | 外側 `SAME_THREAD`、各群のclean開始、既存個別コンテナ | PR CIはSchema→Migration、専用検証はMigration→Schema。`parallel=true` で対象active最大1・最終0。Schema群単独も成功。worker隔離は各JVMの既存コンテナ構造を確認。shardの4プロファイルも成功 |
| AC-5 旧版5件のbackfill/NULL/削除済/不正データ拒否を保持し、完成schemaも実Flyway | `payerUserIdBackfilledFromCreatedBy`、`payerUserIdBackfilledFromOldestAdminWhenCreatedByIsNull`、`backfillGuardFailsMigrationWhenNoFallbackIsResolvable`、`userScopeCreatedByNullDoesNotFailMigration`、`payerUserIdSkipsSoftDeletedOldestAdmin`、完成schema既存7件 | 全件pass。V206の `failingCleanupBlocksNewRequestOnSameContract` と `finalizingCleanupReleasesTheUniqueSlot` を含む |
| AC-6 同条件で初期化を含む時間を比較し、全体半減を主張しない | 固定baseline/after Javaを同一runnerで各1回実行、各事前warm後に対象限定 `test --rerun` を単調時計で計測 | 1072.056606477秒→671.706354359秒、400.350252118秒（37.3441336679%）短縮。必要依存と初期化を含むcommand wall。初期化回数は構造根拠とログ未取得を区別 |

## 同一runnerでの比較・隔離検証

[専用検証run 39943173054](https://github.com/kenta-0420/mannschaft/actions/runs/39943173054) は一時検証branchのhead `d6e20d83b03e0b7099064d50a1dfb5cc0f8ba144` で実行した。[artifact 12499267175](https://github.com/kenta-0420/mannschaft/actions/runs/39943173054/artifacts/12499267175) は571,657 bytes、ZIP SHA256 `25471f22712b6aa783d6676ea990cb14af4b657f10cf533049ad17ea28cc5d8a`。証拠はsummary JSON、対象XML、各段階のlog、sanitized process snapshotと開始時Docker一覧である。

同一GitHub専用runner・JDK21・backend・依存・flagsで、baseline `39421d903351ede5c3ba3ef7a9582df0f5dd5b80` の対象Javaだけへ差し替えた12件と、after `f6ab116b39386f2e7d257afd0cdad4783292dabf` の14件を各1回比較した。各条件でcompileとArchUnitを事前warmし、計測logの `compileTestJava` は双方UP-TO-DATEだった。`archUnitTest` と常時実行の `archUnitFreezeStoreIntegrityTest` は両計測commandに含まれる。Linux `time.perf_counter_ns` によるwallはGradle起動・必要依存・コンテナ起動・Nested BeforeAll・Flyway初期化を含む。事前warmは計測外とする。

共通flagsは `--no-daemon --build-cache --console=plain --max-workers=2 -PexcludeMigrationTests=false -Pmax.parallel.forks=1 -Pfork.every=180 -Ptest.timezone=Asia/Tokyo -PdbReuseValidationOrder=className -PdbReuseValidationParallel=true` と同一initで、対象は `test --rerun` のfilterに限定した。`--tests`、`--rerun-tasks`、依存ゲートの除外は使わない。init SHA256は `a8cad9e3ca2fe158dd6343aab2586f386cd4fb29e096112f696c4723e8680beb`。

| 段階 | 実テスト件数 | failure / error / skip | exit | active最大 / 最終 | command wall（秒） |
| --- | --- | --- | --- | --- | --- |
| baseline | 12 | 0 / 0 / 0 | 0 | 1 / 0 | 1072.056606477 |
| after | 14（Migration5＋Schema9） | 0 / 0 / 0 | 0 | 1 / 0 | 671.706354359 |
| negative cleanup | 2 | 2 / 0 / 0（期待するred） | 1 | 1 / 0 | 302.770626888 |
| 復元後Schema単独 | 9 | 0 / 0 / 0 | 0 | 1 / 0 | 293.369643653 |

各段階のTEST_START/TEST_ENDは12/14/2/9件で一致し、0件の外側suiteを実テスト件数へ加算していない。afterはClassName順のMigration→Schemaであり、並列有効時にも対象methodが重複して動いていない。

negative controlでは所有要求の `DELETE ... WHERE id IN (...)` 1箇所だけに `1 = 0 AND` を加えた。途中失敗後の再利用検査は `SQLIntegrityConstraintViolationException` の `Duplicate entry`（`uk_bphr_open_old_contract`）で失敗し、所有fixture検査は `AssertionFailedError: expected: 0 but was: 1` で失敗した。コンパイル失敗や0件実行をredとはしていない。after原bytesへ完全復元し、復元後Schema9件のgreenを確認した。

復元前後のafter Java SHA256はともに `17cb79fccff1a04161dffe3c9baf7a9ffabf6d71b8a26e5fee6930b8e29d6cc3`、summaryの `restoredExactBytes=true`。開始時は `preexistingJava=[]`、`preexistingHeavyCount=0`、Docker一覧は空、終了時 `ownedJavaRemaining=[]`。cleanupは2026-10-23 20:48:37 JSTに完了し予約枠内で終了した。停止対象はowner marker・PID開始世代・cwdを照合する自所有Javaだけで、他サービスやWSL全体を停止していない。

この比較は単回ペアの概算であり、統計的な再現性や全suiteの37%短縮を示さない。旧nightlyのXML1592.402秒とPRのMigration757.893秒・Schema0.503秒は異なる実行環境のmethod/suite参考値で、初期化込み時間の短縮率には採用しない。元のローカル計測snapshotは変更せず継続中であり、この比較とは別である。

## 通常CI・検分・省略の根拠

[通常PR CI run39922935222](https://github.com/kenta-0420/mannschaft/actions/runs/39922935222) のhead `f6ab116b39386f2e7d257afd0cdad4783292dabf` は、初回shard3が105分timeoutとなった後、失敗分だけの再実行（attempt2）で成功した。全6 shard、backend checks、集約が最終successであり、初回失敗を隠して初回一発成功とは記録しない。

同runの[artifact12491969120](https://github.com/kenta-0420/mannschaft/actions/runs/39922935222/artifacts/12491969120)（30,218,441 bytes、SHA256 `441a93de7349cbf22225b71efc1fb763f250d446d1c0dd06aff6d387d66a5362`）では対象14件がfailure/error/skipすべて0。Schema timestamp `2026-10-23T08:28:12.231Z` / time0.503秒、Migration timestamp `2026-10-23T08:28:12.739Z` / time757.893秒によりSchema→Migrationを確認し、専用検証との両群順を裏取りした。

以前の「本番SQL無変更なので通常PRから対象migration検証が除外され、別nightlyが必要」という記載は誤りだった。`.github/scripts/migration-sensitive-paths.regex` は `^backend/src/test/java/.*/migration/` も対象とし、今回のdetect jobは `migrations_changed=true` と判定した。従って通常PR自体がmigration込みで対象14件を実行している。重複して開始した[nightly run39922963869](https://github.com/kenta-0420/mannschaft/actions/runs/39922963869)も全6 shard・ArchUnit・Summaryが成功した。追加nightlyや同等のローカル全量実行は行わない。

[shard coverage job120312440522](https://github.com/kenta-0420/mannschaft/actions/runs/39922935222/job/120312440522) の完了ログで、重み表あり/なし×migration除外あり/なしの4プロファイル全てについて取りこぼし・重複・migration漏れ・ネスト分断なしを確認した。`build.gradle.kts` の4つの `verifyProfile` 呼び出しとも照合済みで、ローカル同等検証は重複するため省略した。

司令塔による最終静的・AC検分は実害finding0。既存12件の本体・アサーション保持と表示名集合、diffcheckを確認した。全caseの個別起動は、Schema群単独green・共通cleanup経路・回帰2件のred/greenで重複するため追加しない。初期化故障の注入実験は未実施だが、catchなし・初期化済みflagなしの構造と既存不正旧データ拒否passにより評価し、追加の必須runとはしない。

scopeは `test-only`。製品/API/UI/本番SQLの挙動と画面導線を変更しないため、UI実機試験とアリシゼーションは該当対象なしとする。以下の旧実装時点の保留履歴と、末尾の追加実装による最終完了証拠を区別する。

## 最終headのCIと実装merge

実装PR #4068の最終head `df8ee0499ed99f38ec715ea902e761f3e07e635e` に対する[CI run39949628011](https://github.com/kenta-0420/mannschaft/actions/runs/39949628011)は全6 shard・ArchUnit・timezone・shard coverage・集約を含む全jobがsuccess。対象Javaを変えない最終文書更新のため、他5 shardはFROM-CACHE、shard3は実行して成功した。cache再利用を新たな全対象実走とは扱わず、先行する14件実走と専用検証の証拠を併せて評価した。

GitHub APIで実装PRのheadと成功runのhead一致を確認し、2026-10-23T13:41:29Zにsquash mergeされたことを確認した。merge commitは `cf1d10173b14f657a1ac9b4f6706491878730c83`。完了証拠で使う実装headはmerge commitではなく最終PR headである。この旧実装時点では次のゲート条件が未成立で、完了JSONを登録しなかった。cleanupのnegative/green証拠はAC-2と本記録に対応付ける。

## 旧実装PR #4068の完了証拠ゲート拒否履歴

[記録PR #4071](https://github.com/kenta-0420/mannschaft/pull/4071) の[Task-List PR Gate run39964552315](https://github.com/kenta-0420/mannschaft/actions/runs/39964552315/job/120450922052)は、実装headの再チェックがマージ時点で実行中だったため完了遷移を拒否した。JSON・台帳7列・証拠参照の不整合や一過性APIエラーではない。

GitHub APIの時刻は次のとおり（UTC）。

| 対象 | 開始 | 完了/merge | 結果 |
| --- | --- | --- | --- |
| [再check run39963270169 / check120446444276](https://github.com/kenta-0420/mannschaft/actions/runs/39963270169/job/120446444276) | 2026-10-23T13:41:16Z | 2026-10-23T13:41:32Z | Task-List PR Gateは最終successだが、実装mergeより3秒後に完了 |
| 実装PR #4068 | — | 2026-10-23T13:41:29Z | squash merge |
| [先行check120401374560](https://github.com/kenta-0420/mannschaft/actions/runs/39949851343/job/120401374560) | 2026-10-23T11:58:17Z | 2026-10-23T11:58:34Z | success |
| [先行check120400620903](https://github.com/kenta-0420/mannschaft/actions/runs/39949624387/job/120400620903) | 2026-10-23T11:56:21Z | 2026-10-23T11:56:35Z | success |

司令塔がready後に開始した再チェックの完了を待たずmergeした経緯を確認した。完了証拠規約は「マージ前に開始し、マージ後に完了したcheck」をマージ時点の実行中として拒否し、同名の先行successでは救済しない。通常backend CI run39949628011の成功とコード改善の実測結果は有効だが、これをもって完了ゲート合格とはしない。

この拒否時点では台帳を実装中のまま、コード改善は反映済み・完了証拠ゲート保留として記録した。新規の未merge完了JSONは記録PRから除去した。validatorや判定閾値を緩めず、実装SHA/時刻の偽装、no-op実装PR等による迂回、同内容の無意味な再試行は行わない。

今回差分の直接構造検査と実API照合・diffcheckは実施した。正式ローカルCLIは過去証拠の走査が長時間化したため、PID・開始世代・commandを照合した自所有nodeプロセスだけを停止し、未完了として記録する。元のローカル計測・他JVM・共有サービス・WSLは停止していない。変更していないvalidator unitの初回17/20と開始済み再確認18/20は疎checkout参照不足と既存性能閾値による限界をPRに記録し、unit全量greenとは扱わない。

## 追加改善と最終完了証拠

追加実装PR [#4075](https://github.com/kenta-0420/mannschaft/pull/4075) は
head `0daa7a81c6d2135a6eb942b27aa781d0c2fbae26` を対象とする。
merge commitは`6ffe4fc9deba8e6271f377f4054efa6bec3c4196`、merged_atは`2026-10-23T17:02:17Z`。root最終検分はfinding0、通常CIと専用実結果を照合した。全24checkはmerge前に完了し、同名最新世代もsuccess/予定skip、statuses=[]。最新check完了は16:57:17Zで、公式`summarizeImplementationChecks`でもactual merge時刻とraw原本から合格を確認した。

Team・Organizationの不要なSpring設定2つを撤去し、Surveyと共通MySQL基底へ寄せた。
metadata回帰はafter全体を固定し、Team/Organizationの2Javaだけを`d4cc6d7f`へ差し替えるbaselineで、Context load/static初期化をせず
構成key3→1のred/greenを検証した。実DBの既存16caseと全assertion・rollback・Docker条件を保持した。
Contact期限切れ招待の既存caseはtoken生成だけを製品と同じ36字UUIDへ是正し、期待404や認可を変更していない。

[専用run39973755063](https://github.com/kenta-0420/mannschaft/actions/runs/39973755063) は
検証workflow head `c359dcba98bfd2c4245da5c4c69aa4600d87d2ce` で実行し、対象afterを上記実装headへ固定した。
このworkflow headは実装headと異なるので、完了JSONの `ci[]` には登録せずAC証拠に対応付ける。
metadata-redは1件のexpected failure、validated=true、496.577358035秒。
metadata-greenは2pass・skip0、validated=true、233.988271905秒で、metadataDockerEmpty=trueだった。
実DB baselineは16pass・skip0、487.615790897秒、JPA初期化3回。
ただしhelperがsuite日本語表示名をFQCNと比較して停止し、run全体は15:24Zにfailure、afterの実DBは未実行である。
このbaseline片側だけを、新しい完結したペアのbaselineとして流用しない。

証跡[artifact12511902784](https://github.com/kenta-0420/mannschaft/actions/runs/39973755063/artifacts/12511902784)は
58925 bytes、SHA256 `37b41cad010177327f140bdbf862adcde66901fb4f76a089c6a860171fe559c7`。
cleanupは2026-10-23T15:23:55.048681Z、exactRestore=true、ownedJavaRemaining=[]、Docker空。
全体failureを隠さず、有効と確認したmetadataのred/greenだけをAC-8に使用する。

最小helper修正後の[run39978413208](https://github.com/kenta-0420/mannschaft/actions/runs/39978413208)
（workflow head `2b9d10b7bb7908af4f01d52a1c9282696068a9ad`）はrunner未取得steps[]のまま
25分の開始予算を割りrootがcancelした。結論はcancelled、実テストは走っていない。

新performance-only [run39979684252](https://github.com/kenta-0420/mannschaft/actions/runs/39979684252)
（workflow head `d54df6bfc291d93517e72dd076771057b63d0af0`）は最終success。
[親Issue予約comment](https://github.com/kenta-0420/mannschaft/issues/4022#issuecomment-6368577969)で
2026-10-24 01:00〜02:00 JST、01:58停止/02:00cleanupを予約した。
metadataは先行有効証拠を使い反復せず、新しいbaseline/after性能ペアを比較した。
新ペアは双方16pass、failure/error/skip0、FQCN付きcase名集合は完全同一、START/END各16。
active最大1・最終0、実行順は双方Organization→Survey→Team、XMLのEMF初期化は3→1回だった。
同一COMMON条件でcompileJava/compileTestJavaはUP-TO-DATE、archUnit・常時Integrity・Jacocoは実行された。
baseline-warm402.301895314秒・after-warm183.549323278秒は各performance wallから除外した。
command wallはGradle起動、test、依存・後処理（常時Integrity/Jacocoを含む）の全体時間であり、純クラス/DB初期化時間ではない。
405.597496404秒→294.490044928秒、111.107451476秒（27.3935249751%）の対象単回短縮だった。
baseline→after固定順の単回概算で、事前warmはcompile/ArchUnit系のみ、OS/Docker image等のcache温度の影響を独立分離していないため27.39%全てを初期化削減の因果効果とは断定せず、ログで直接確認した実初期化3→1と区別する（image pullの有無はログ明細不足のため未確定、追加反復なし）。

開始時Java=[]・heavy0・Docker空、原bytes復元true/hash同一、Java残=[]・Docker空。
cleanupは2026-10-23T16:21:28.993883Zで期限内、予約残枠は親Issue comment6369592116で解放した。
結果はPR4075 comment6369593874に保存され、実装headと本文は変更していない。
[artifact12514644104](https://github.com/kenta-0420/mannschaft/actions/runs/39979684252/artifacts/12514644104)は
84668 bytes、SHA256 `dad3a6546ae338bf2129d40cf2081576b607be0ced27137cdb093ad601739bf5`。
Git blob原本のcanonical LF init SHA256 `b0e82834ea272ad6dcc1f8a35297c9495d30377b740dd371b9b59e9a1936c8bf`は実run summaryと一致した。
Python原本SHA256 `dcf053915198dc00641c68003fe04aa28ab155bb441b0925b221d0d07f6d2645`、
workflow原本SHA256 `7cc41c67290163ae38d2b62053b119f94df257d9dda95ace2b4f9d76485357f1`も保存している。
これらはWindows作業コピーのSHAと混同しない。
これらの専用workflow runはすべて実装headと異なるため `ci[]` に入れない。

| 比較対象 | baseline wall（秒） | after wall（秒） | 起動数 | 結果 |
| --- | --- | --- | --- | --- |
| 既存Payer単回（run39943173054） | 1072.056606477 | 671.706354359 | 再生削減は構造根拠 | 14pass・対象command37.3441336679%短縮 |
| 先行visibility3ITの片側（run39973755063全体failure） | 487.615790897 | 未実行 | 3→未実行 | baseline16pass/skip0のみ。ペア比較に不採用 |
| 新visibility3IT単回（run39979684252） | 405.597496404 | 294.490044928 | EMF3→1 | 双方16pass/skip0・対象command27.3935249751%短縮 |

両測定は対象と実行時期が異なり、合算した改善率や全量への外挿は行わない。
旧Payer検証revision `f6ab116` と今回after `0daa` の比較では、`backend` のtest外、
Payer Java、`backend/src/test/resources` の差分はいずれも空だった。
製品・backend依存設定・旧Payer実装・共通test resources不変を確認し、旧Payer14件の実走証拠を保持する。
全量はQPC42170.927秒で期限停止し、通常testのXMLは0件、残り件数も不明のままである。
元L0M CMP-261021-1602と旧context CMP-261004-2047の全体完了を宣言しない。

### 通常CIとmerge時点の照合

通常Backend [run39973223947](https://github.com/kenta-0420/mannschaft/actions/runs/39973223947) は
同一repo/head・最終successをAPIで確認し、全6shardとaggregateを含む成功runとして完了JSON `ci[]` に登録した。専用検証workflowのrunはheadが異なるためACリンクだけに用いる。
閾値を変えず、skip/cache/未実行を実走greenへ読み替えない。

通常CIのshard3はsuccess。[artifact12517046574](https://github.com/kenta-0420/mannschaft/actions/runs/39973223947/artifacts/12517046574)
のZIP SHA256は`530dfe5831cd8cbcbfd5288fd82e7969a34d1ac3e56649b8a9c5fd44c58b390b`。
`com.mannschaft.app.auth.mfa.MfaBackupCodeLatencyIT` のXMLは4件、failure/error/skip0、
timestamp `2026-10-23T15:44:16.526Z`。
「AC-54 旧BCrypt 8件 最後一致は3秒以内」と「AC-54 旧BCrypt 8件 全不一致は3秒以内」の
両testcase実在と成功を確認し、既存2件は今回CIで閾値不変の実走greenとする。
これは旧ローカル遅延の原因究明・解消を示すものではない。
XMLのcase timeはfixture込みでassert対象の計測と異なるため、閾値比較には使わない。
[Contact artifact12517861399](https://github.com/kenta-0420/mannschaft/actions/runs/39973223947/artifacts/12517861399)は34131690 bytes、ZIP SHA256 `3bd90dffaf6b65ae277c969db7ad52367e9a960823cd7fbc879f1a4c01514f87`。ContactScopeContractITは全nested11XML/51pass、failure/error/skip0。AcceptInviteのXMLは3件、timestamp2026-10-23T16:10:04.601Zで、期限切れトークンの404ケースが実在しskip/failureなし。既存ケース自体を回帰として、fixture修正後のAPI期待値まで実走greenを確認した。旧失敗の具体値や生ログは公開しない。

最後のPR本文編集後のTask-List PR Gateを含め、merge前開始checkが全てmerge前に完了し、
同名最新世代がsuccess/neutral/skippedであることを最終API snapshotで照合した。
旧PR #4068の2026-10-23T13:41:29Z mergeに対し、再checkが13:41:32Z完了してゲート拒否された
経緯は消さない。先行successで救済せず、validator・SHA・時刻・閾値を偽装しない。

### 完了記録

追加実装のmergeと最終検証後、この別記録PRで証拠JSONを追加し台帳を完了へ遷移する。旧4068のゲート拒否は過去の正しい拒否として保持し、追加実装4075の実際のmerge証拠によって今回の完了判定を行う。
JSON `implementation.sha` は追加実装のPR headを使い、merge commitや完了記録PR headを使わない。
scopeはtest-only、review passedは最終root検分の根拠に対応させる。
UI実機・アリシゼーションは画面/API/導線の変更がないため同scopeのnot-applicableとする。
公式completion CLIの`--input`へ今回差分と実APIメタデータだけを渡し、構造・参照・実装PR/CI照合を実施する。記録PRのtrusted Task-List PR GateはそのPRで別途確認する。
