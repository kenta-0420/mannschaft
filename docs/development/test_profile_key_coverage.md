# テストプロファイルの設定到達性（CMP-260910-1811）

`src/main/resources/application-test.yml` は bootRun 用で、Gradle test では同名の
`src/test/resources/application-test.yml` が先に解決される。下表は是正前の main 専用キーである。
本番コード・main 設定・DDL は変更しない。

| main 専用 leaf キー | 是正前の test 実効値 | 対応と理由 |
|---|---|---|
| `feature.shift-budget.enabled` | 共通設定の `false` | `true` を test に宣言し、実 Bean の予算経路を検査する |
| `feature.shift-budget.monthly-close-cron-enabled` | 共通設定の `false` | `false` を明示。月次締めは試験から手動実行する |
| `feature.shift-budget.retry-batch-enabled` | 共通設定の `false` | `false` を明示。定期 retry を自動起動しない |
| `spring.datasource.url` | IT の `@DynamicPropertySource` が供給 | 同期しない。main の JDBC Testcontainers URL と実 MySQL の動的接続を混ぜない |
| `spring.datasource.driver-class-name` | 実 JDBC URL から MySQL driver を解決 | 同期しない。main の `ContainerDatabaseDriver` は上記 URL 用である |
| `mannschaft.line.signature-verify.mode` | 共通設定の `log-only` | test 用の `off` を明示。署名検証自体の試験は個別に設定して担う |
| `mannschaft.security.internal-signing-key` | 共通設定の空文字（環境変数未指定時） | main test と同じダミー固定鍵を宣言。実秘密鍵は使用しない |
| `mannschaft.error-monitoring.aggregation.enabled` | 共通設定の `true` | `false` を明示。試験中の外部 Slack 集約を自動起動しない |
| `mannschaft.error-monitoring.health-polling.enabled` | 共通設定の `true` | `false` を明示。試験中の Health 監視を自動起動しない |
| `pointcard.rematch-batch.enabled` | 共通設定の `true` | `false` を明示。定期再マッチ用 Bean を試験で自動登録しない |

## 既存試験が実際に検査している範囲

是正着手時（main `d9da2dc5f0`）の shiftbudget 配下は35試験ファイル（トップレベルクラス）である。
起票時の「29ファイルが全て機能無効のまま green」という説明を、現在の試験全体へ一律には適用できない。
Mockito は YAML を読まず、slice 試験は業務 Service を置き換えるため、これらの green は
グローバル機能フラグの到達性を実証しない。DB の `FeatureFlagTestSupport` も別ゲートであり、
`feature.shift-budget.enabled` の Spring binding を代用しない。

| 構成 | 既存試験クラス | 守っている契約／YAML との関係 |
|---|---|---|
| Mockito（19） | `ShiftBudgetEndToEndIntegrationTest`、`ShiftBudgetRetryBatchJobTest`、`ShiftBudgetHourlyRateMissingNotificationListenerTest`、`ShiftBudgetPublishedEventListenerTest`、`ShiftBudgetThresholdAlertNotificationListenerTest`、`BudgetThresholdAlertServiceTest`、`HourlyRateAggregationServiceTest`、`MonthlyShiftBudgetCloseServiceTest`、`ShiftBudgetAllocationServiceTest`、`ShiftBudgetCalcMultiTenantTest`、`ShiftBudgetCalcServiceTest`、`ShiftBudgetConsumptionServiceTest`、`ShiftBudgetFailedEventServiceTest`、`ShiftBudgetMultiTenantTest`、`ShiftBudgetNotificationResendServiceTest`、`ShiftBudgetRetryExecutorTest`、`ShiftBudgetSummaryServiceTest`、`ThresholdAlertEvaluationServiceTest`、`TodoBudgetLinkServiceTest` | 計算・呼出し・通知・失敗・テナント分岐を mock 境界内で検査。名称が EndToEnd でも実 Spring/DB の証拠にはしない |
| WebMvc slice（6） | `BudgetThresholdAlertControllerTest`、`MonthlyShiftBudgetCloseControllerTest`、`ShiftBudgetAllocationControllerTest`、`ShiftBudgetCalcControllerIntegrationTest`、`ShiftBudgetFailedEventControllerTest`、`ShiftBudgetSummaryControllerTest` | HTTP 入出力・例外応答。業務 Service は mock なので実フラグ判定を通らない |
| 手動生成（1） | `ShiftBudgetFeatureServiceTest` | `ShiftBudgetProperties` の値を直接設定し、global×組織の三値論理を検査。YAML binding は対象外 |
| 移行専用（1） | `FlywayExistingDataBudgetMiscSetNullFkMigrationTest` | Flyway と実 DB の FK 契約。業務機能フラグは対象外 |
| 個別 true 指定の実 MySQL（4） | `MonthlyShiftBudgetCloseTransactionIT`、`ShiftBudgetConsumptionCancelRaceIT`、`ShiftBudgetMultiParentIT`、`ShiftManualArchiveConsumptionCancelIT` | 月次締め・取消競合・複数親・手動アーカイブを実 Bean/DB で検査。是正前から個別指定で有効だったため「全件無効」は誤り |
| 共通 YAML の実 MySQL（4） | `ShiftBudgetConsumptionReconcileBatchIT`、`ShiftBudgetThresholdAlertNotificationBoundaryIT`、`ShiftBudgetAllocationRepositoryTest`、`ShiftBudgetConsumptionRepositoryTest` | 整合バッチ・通知イベント配送境界・Repository を検査。無効状態の一般業務入口を実証するものではなく、既存消化の救済、直接イベント、直接 Repository が主対象 |

## 番人と実行時の証明

`TestProfileKeyCoverageGuardTest` は両ファイルを Spring の YAML パーサーで flatten し、
main の leaf キーが test から欠落すると失敗する。値差と test 専用 fixture キーは許容する。
意図的省略は上記 DataSource の完全キー2件と理由だけに限定し、免除の不要化も検出する。
入れ子・同じ prefix の未免除キー・配列要素・値差の陽性／陰性検体を併設する。

`ShiftBudgetMultiParentIT` の個別 true 指定を外し、共通 YAML を使う実 Spring context の
`ShiftBudgetProperties.isEnabled()` を fixture 作成前に確認する。そのまま既存の
予算割当・消化・複数組織・認可・競合経路を検査する。残る個別 true の3 IT は、
業務契約の証拠として扱い、共通 YAML 到達の証拠へ転用しない。

`SelfInvocationRequiresNewTransactionIT` は再マッチバッチの実トランザクション境界を検査するため、
当該 IT だけ `pointcard.rematch-batch.enabled=true` を明示する。共通設定は `false` を維持し、
test プロファイルの scheduler も無効のまま、実 Bean の直接呼出しで既存6ケースを検査する。

検証は、設定同期前の番人 RED（欠落8件、自己検体は PASS）、同期後の番人 GREEN、
上書き無しの実 Spring/実 MySQL の GREEN、正式 Backend CI 全6 shard の波及確認を区別する。
上記トランザクション IT の6ケースも非 skip の成功を確認する。
実行結果が未取得・skip の場合は該当段階の合格としない。

### 設定同期前の実行結果

番人追加コミット `c3d4232adb77071463598ca8a64bdcbf5e0ae7fa` に対し、
`scripts/gradle-turnstile.sh ./gradlew test --tests com.mannschaft.app.config.TestProfileKeyCoverageGuardTest --console=plain`
を backend ディレクトリから実行した。
`:test FAILED` と原 JUnit XML で6件中、自己検体5件成功・実差分1件失敗・error/skip 0件を確認した。
意図した失敗の欠落キーは上表の DataSource 2件を除く8件と完全一致した。
原 XML の SHA-256 は `05036981aa5f94af57c8025684679c84b3d2a1ca827f7bf6e3b9714abd2caaa9`。
この RED は test タスクの結果である。後処理 JaCoCo の完了後、Gradle 全体も
`:test` の意図した失敗を理由に exit 1（`BUILD FAILED in 3h 47m 4s`）で自然終了した。
設定同期後の番人、実 Spring/実 MySQL、全6 shard の GREEN は、正式 CI 結果の取得後に記録する。

初回同期後の [Backend CI 42557651582](https://github.com/kenta-0420/mannschaft/actions/runs/42557651582) は未合格。
shard 5 の上記トランザクション IT が再マッチ Bean 不在による context 起動失敗で6件失敗し、
業務アサーションへ到達しなかった。個別 Bean 有効化後の GREEN は次回の正式 CI で確認する。

製品の API・UI・導線を変更しない純 test＋docs のため、実機 E2E と住民探索は対象外。
ここで担う到達性はブラウザー導線ではなく、実際の Spring テストクラスパスで検証する。
