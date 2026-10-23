# CMP-261023-1648 テストDB再利用の検証記録

## 対象と現在の状態

- Issue: [#4066 テストDB再利用とFlyway再生重複の削減](https://github.com/kenta-0420/mannschaft/issues/4066)
- CMP: `CMP-261023-1648`（実装中）
- ユーザーの2026-10-23優先指示: DBを再利用できるテストは再利用し、FlywayによるDB作成の短縮を「なるべく早めに」実施する。
- 初手の対象: `backend/src/test/java/com/mannschaft/app/billing/migration/BillingPayerHandoverFoundationFlywayIT.java`
- 実装コミット: `9cbe0868ba2d61d8e8f453482a5eab2c6327c46a`
- この文書は実行前の初期記録であり、受け入れ条件を合格とは判定していない。

既存の共有基底クラスを変更せず、新たな共有基底も作らない。対象クラスが従来から所有するMySQLコンテナ1個の起動・停止ライフサイクルを維持する。永続キャッシュ、Gradle設定、製品コード、API、UI、本番SQL、migrationのchecksumは変更しない。

## 変更内容と構造上の目標

旧版データを移行する既存5件を `MigrationTests` に配置し、各検証前に `clean → V202 → 旧契約fixture` を実施する。完成schemaを検証する既存7件は `SchemaTests` に配置し、`PER_CLASS` の `BeforeAll` で `clean → V202 → 旧契約fixture → 現行末尾` を1回実施する。群が逆順でも他群の残存schemaを信用しない。

完成schema群では `BeforeEach` と `AfterEach` で所有する固定IDの要求・追加契約だけを子→親の順に削除する。旧契約、未知のfixture、他のテーブルを一括削除しない。外側の `Execution(SAME_THREAD)` で同じschemaを使う検証を逐次実行する。

既存12件を保持し、fixture隔離の回帰検証2件を追加した合計14件とする。構造上、全履歴の初期化は12回から6回、Flywayの `migrate()` 呼び出しは24回から12回への削減を目標とする。これはソース上の呼び出し構造であり、実行ログによる回数確認・時間計測は未実施である。全テスト時間の半減を主張するものではない。

`target(null)` による現行末尾への実Flyway適用は維持する。V206の `FAILING_CLEANUP` と生成列UNIQUEの契約も検証するため、V203で適用を止めない。

## 受け入れ条件と検証の対応

以下の6条件はいずれも実行による確認がまだ済んでいない。

| 条件 | 対応する実装・テスト | 検証予定と現在の状態 |
| --- | --- | --- |
| AC-1 既存12検証を保持し、完成schemaの初期化は1回、2件目以降にclean/migrateしない | `MigrationTests` の既存5件と `SchemaTests` の既存7件。完成schemaの初期化を `initializeCompletedSchema` に集約 | 14件の実MySQL実行、Flywayログの初期化回数確認、従来版との同条件比較。実行未確認 |
| AC-2 所有fixtureだけをcleanupし、空・単独・逆順・途中失敗でも汚染せず未知fixtureを保持 | `完成schemaのcleanupは所有fixtureだけを削除する` は空・繰り返しcleanup、所有要求/追加契約の削除、無関係な契約/要求と旧契約の保持を検査。`完成schemaのcleanupは途中失敗後も再利用できる` は重複INSERT失敗後の再要求を検査 | 回帰2件、Schema群/各caseの単独実行、逆順実行。要求DELETEを一時的に無効化するnegative controlで失敗を確認し、復元後greenを確認する予定。すべて未実行 |
| AC-3 初期migration失敗はJUnit失敗とし、握りつぶしや初期化済み扱いにしない | `initializeCompletedSchema` と旧版準備はmigrate例外を捕捉しない。`backfillGuardFailsMigrationWhenNoFallbackIsResolvable` は解決不能な旧契約で `FlywayException` を期待する既存検証を保持 | 実MySQLで旧版の異常拒否を確認し、初期化失敗のJUnit報告も確認する予定。実行未確認 |
| AC-4 同schemaは逐次、群順の両方向で各群の初期化が自立しworker間はコンテナで隔離 | 外側 `Execution(SAME_THREAD)`、各群がそれぞれcleanから開始、既存コンテナの個別ライフサイクルを維持 | 群の正逆実行、Schema群単独、並列設定での逐次動作・worker隔離、`verifyShardCoverage` の重み表あり/なし×migration除外あり/なし4プロファイル。すべて未実行 |
| AC-5 旧版5件のbackfill/NULL/削除済/不正データ拒否を保持し、完成schemaも実Flywayで検証 | `payerUserIdBackfilledFromCreatedBy`、`payerUserIdBackfilledFromOldestAdminWhenCreatedByIsNull`、`backfillGuardFailsMigrationWhenNoFallbackIsResolvable`、`userScopeCreatedByNullDoesNotFailMigration`、`payerUserIdSkipsSoftDeletedOldestAdmin`。完成schemaの既存7件も保持 | 既存12件すべてを実MySQLで検証し、V206の `failingCleanupBlocksNewRequestOnSameContract` と `finalizingCleanupReleasesTheUniqueSlot` も確認する予定。実行未確認 |
| AC-6 同条件でクラス全体の時間と初期化回数を確認し、全体半減を主張しない | 比較用snapshotを保全し、変更前後を同条件で計測する計画 | コンテナ起動とNested BeforeAllを含むクラス全体のwall時間およびFlywayログを記録する予定。XMLのmethod時間合算だけを全体時間と扱わない。時間・回数とも未実測 |

回帰検証で使用する無関係fixtureも、この検証自身が所有する専用固定IDに限定する。検証終了時には専用の `finally` で削除し、後始末の例外が元の検証失敗を隠さないようsuppressedとして保持する。

## 実行証跡と残件

現時点では静的な既存12件の本体・アサーション保持確認と `git diff --check` のみ実施した。コンパイル・自動テストの成功を示す記録ではない。

通常のPR CIは本番migration SQLを変更しない場合にmigrationパッケージを除外するため、そのCIがgreenでも対象14件を検証済みとはしない。既存の `backend-nightly-full.yml` は `workflow_dispatch` とmigration除外なしの全量実行を備える。実装branchへ別途dispatchし、対象クラスの実行件数・失敗・skip・Flywayログを確認する必要がある。共通concurrencyによる他の実行の取消しを避けるため、dispatch前に実行中runを確認する。

ローカルのnegative control、正順/逆順、単独、並列設定、shardの4プロファイル検証はすべて未実行。時間比較用のsnapshotを保全しており、同じPCの別計測への干渉を避けるため、この実装担当からGradle・ビルド・Docker・WSLの重い処理はまだ起動していない。

本変更のscopeは `test-only`。製品/API/UI/本番SQLの挙動と画面導線を変更しないため、UI実機試験とアリシゼーションは該当対象なしとする。実MySQLによるfixture隔離・移行・制約の検証は省略せず、上記の未実行残件として扱う。
