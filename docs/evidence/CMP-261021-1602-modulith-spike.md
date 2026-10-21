# CMP-261021-1602 L0-S Modulith 実検証証拠

2026-10-21。捨てブランチ `spike/cmp-261021-0017-modulith-261021` のスパイク。コードは main へマージしない。対象正本は `docs/architecture/modular_monolith_boundary_debt_repayment.md` §5.1/9.1 の8項目。§0の決定は変更していない。

作業worktree: `C:/Claude/mannschaft/.claude/worktrees/agent-4022-modulith-spike`。起点main `3bad46f846cd094a64bd46524aed69479b0c76bf`、最終追従main `ccfcb19b6e961cbb48d1ba7893fbad81f7fcd65d`。実測コードSHAは下表を正本とする。

## 結論と検証範囲

| 項目 | 実測結論 |
|---|---|
| 1 Boot互換版 | **Modulith 1.4.13**。公式1.4互換表のBoot3.5対応に加え、実Boot3.5.13でRegistryのMySQL/非同期ITが4/4成功。全Modulith依存も1.4.13へ解決 |
| 2 JPA Registry | 実Entity生成DDLは下記6列、索引はPKのみ。attempt/state列なし。公式 `completion-mode=delete` で実行前1行→完了後0行のIT成功 |
| 3 対象限定/非TX非同期 | 標準状態の認証保存1件と一般listener保存で実red。その後 `ReliableEvent` **かつ**対象注釈の配信だけに絞り、4/4 green。専用executor/tx=false/成功完了/例外未完了を実確認 |
| 4 ArchUnit1.3.0 | 既定core1.4.2。core/junit5-api/engine/engine-apiを1.3.0へ固定した依存解決と全本番構造4/4が成功。実JVMのImplementationVersion/CodeSourceも既定1.4.2・pin1.3.0、Modulith1.4.13と確認。専用taskはJupiter engineのみで、既存ArchUnit engine/freeze番人全体は未実行 |
| 5 全本番違反 | 本番compiled/imported **12,396/12,396**、120module。全報告23,691。発生元別依存entry23,603、global unique依存23,591（モジュール間の重複12。最終pinでモジュール内重複0も実測）。common起点raw875（最終pinのuniqueも875） |
| 6 抽出/橋渡し | 公開APIの発生元別依存＋完全direct graphのSCCで判定。webhook起点raw4（最終pinのuniqueも4）。循環SCCは1成分、common参加あり/webhook参加なし。橋渡し注釈の端点だけの除外で消える検体と、別common辺が残る陰性検体が成功 |
| 7 最小パターン | jobmatching→chat::apiだけ、イベント/購読はjobmatching自身。modules.verify成功、循環0 |
| 8 webhook | **STANDALONE起動不可**（PasswordEncoder不在）。公式DIRECT_DEPENDENCIESも起動不可（実AccessControlService→UserRoleRepository不在）。各1test起動失敗。認可mock/本番変更なし。時間とcontext観測範囲は後述 |

項目1/3/4について、ここで実証した範囲では§14の「不可」は生じていない。項目8は成功扱いしない。後続導入では実際の全beanからの対象listener探索、既存ArchUnit番人/engine/freezeの同値CI、webhookの依存境界を別途満たす必要がある。

## 実行コードと結果の対応

各結果は `backend/build/spike-evidence/` 以下のローカル保全物。大量のraw違反/HTML/XMLは文書PRへ転載しない。各runのEXECUTION_SHA、XML、UTF-8実行log、schema/structure出力を対応させた。

| code SHA | 実行/判定 | ローカルrunディレクトリ |
|---|---|---|
| `0809ba747aaf9aab5bab1282ad912dde0790f52a` | Registry4件=成功2/失敗2/errors0/skip0、**実red**。53.90秒 | `native-registry-red-0809/` |
| `2a822a4eb7878a7b3ce18ff8a44643d34ff3a6c0` | Registry4/4 green。Structure3/4（取り込み検体の不備）、Webhook STANDALONE1件起動失敗。全体226.91秒/exit1 | `native-validation-2a82/` |
| `5f8bac8a71a27cd3ecc4eb2c6f9a70fde75f64f4` | RegistryDelete1/1、Structure4/4 green。Webhook2mode各1件起動失敗。全体355.10秒/exit1 | `native-validation-5f8b/` |
| `9a7b47cf7c79bcc96d6dde96f070218fa30e26d3` | pin1.3.0構造4/4、global unique集合一致もgreen。111.93秒/exit0 | `native-pin-9a7b/` |
| `084279db3f7de80b1ca20ba5e917fc493d29b544` | 通常構造4/4 green、Webhook2mode各1件起動失敗。版/CodeSource/cache最終統計取得。308.76秒/exit1 | `native-validation-0842/` |
| `afb850b6f7719d24d142a80f6b159b3d7786be0a` | 最終pin構造4/4、実JVM1.3.0、module内/間重複を確認。104.07秒/exit0 | `native-pin-afb8/` |

各Test taskは独立JVM。全体exit1を全検証失敗と読み替えず、タスク別XMLを判定する。5f8bのroot suite壁時計はDELETE44.262秒、Structure92.898秒、Webhook STANDALONE103.080秒、DIRECT98.027秒。最終084はStructure110.582秒、STANDALONE89.971秒、DIRECT99.141秒、最終pinは99.439秒。全体時間はgate取得後のGradle呼出し全体で、compile/JVM/container等を含む。XMLのWebhook suite時間0.012/0.007秒は起動時間ではない。

## 試練→red→限定実装→green

| 条件 | テスト名 | 標準状態0809のred | 限定後2a82 |
|---|---|---|---|
| 既存認証イベントを保存しない | 既存認証イベントは購読されても保存0件 | expected0/actual1で失敗 | 成功 |
| 対象注釈の配信だけ保存、非TX専用非同期で完了 | ReliableEventの対象購読だけ保存し非TX非同期の正常終了で完了する | module-successに加えordinary-reliableが保存され失敗 | 成功 |
| 例外処理後も未完了 | 非TX非同期の例外は未完了のまま残る | 既存挙動の性質確認として成功 | 成功 |
| 公式Entityの実DDL/索引 | 公式Entityの実MySQLスキーマと索引を保存する | 既存挙動の性質確認として成功 | 成功 |

未実装状態のassertion redを確認してから、`5c5e2a6261` で公開 `DefaultEventPublicationRegistry.store(Object, Stream<PublicationTargetIdentifier>)` 拡張点を利用した最小検体を追加した。`ReliableEvent` 判定と、実 `@ModuleEventListener` メソッドからmerged `@TransactionalEventListener.id` を得た集合のAND条件。イベント型だけで絞らない。

標準Registry beanにはConditionalOnMissingBeanが無く併存する。公式 `EventPublicationConfiguration` はmulticaster/completion advisorの双方へ `ObjectFactory<EventPublicationRegistry>` を注入するため、限定Registryを `@Primary` とした。interface beanの同一性と、同じ限定RegistryのmarkCompletedへの到達を実assertした。観測overrideは公式同様REQUIRES_NEWでsuperへ委譲。listener内はtx=false、thread名はmodule-spike-であることをassertする。

Listeners.class固定は**最小検体の対象列挙**であり、本番全bean探索の完成品ではない。対象外の認証payloadはログ/証拠へ出していない。

red以前の失敗は合格根拠に含めない。CI39581479427はJava文字literal構文、39583011936はworkerがKotlin EmptySetをdeserializeできない初期化失敗、39584613816はrunner未割当で取消。native3175は計測listenerのOrdered宣言、b445はasync proxyフィールド直接参照による準備NPEで修正。599cは公開SPIがruntime依存にだけありcompile不可のため、spikeImplementation events-core1.4.13を明示した。いずれも実assertion redと区別した。

## §11.2の確定材料

Modulith1.4.13公式JPA EntityをMySQL8.0へHibernate create-dropし、SHOW CREATE TABLE / SHOW COLUMNS / SHOW INDEXで実測した。以下が**公式既定の実測**。varchar512/TEXT/未完了検索索引を公式生成物として扱わない。

```sql
CREATE TABLE event_publication (
  completion_date DATETIME(6) NULL,
  publication_date DATETIME(6) NOT NULL,
  id BINARY(16) NOT NULL,
  event_type VARCHAR(255) NOT NULL,
  listener_id VARCHAR(255) NOT NULL,
  serialized_event VARCHAR(255) NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

- 非同期正常完了はUPDATEをpropertiesで明示して実測。未指定時の既定UPDATEは公式情報、実行中のhot切替とARCHIVEは未測定。完了削除は `spring.modulith.events.completion-mode=delete`。RegistryDeleteSpikeITは公開storeを実tx内で実行して未完了1行を確認し、markCompleted後0行を確認した。非同期経路の完了は別のRegistry4testで確認している。
- 試行回数・処理中/失敗/隔離状態列は無い。§11.2の条件に該当し、補助表はL1-Mで実装を要する。このスパイクでは追加しない。
- `(completion_date, publication_date)` 索引はプロジェクト側の追加案であり公式既定に存在しない。L1-Mのmigration/query検証で決める。
- serialized_eventの255文字制約はpayload設計の制約。TEXT化等が必要ならプロジェクト側DDL拡張として判断する。L0-Sでは自由記述を追加せず、列を黙って拡張しない。
- Entityはライブラリpackage内。UuidV7Entity化やEntityScan追加は行っていない。

schema実ログ: `native-registry-red-0809/docs/evidence/CMP-261021-1602-modulith-spike-schema.txt`。

## 構造の計数・採用する抽出手順

1. 本番class locationだけをimportし、root直下と各module.getBasePackage().streamのunionをcompiled main全classと完全一致照合する。inner/generated classを含みpackage-info/module-infoは双方から除く。JavaPackage.stream自体は保持classesを返すが、getRootPackagesはtoSingleであるため子packageを単独では数えない。
2. 各ApplicationModule.detectDependencies(modules)を呼び発生元を確定する。global非循環message集合とorigin union集合の完全一致をassertする。Violations.and→unionByMessageはmessageで重複排除するため、origin entries23,603とglobal unique23,591を区別する。
3. 報告循環はSlice名から参加moduleを抽出する。**reportedCycles=100は検出上限付きの報告数**で、全単純循環数ではない。通常084はcommon参加100/webhook参加0、最終pinはcommon参加99/webhook参加0。上限付きサンプルの差であり、循環message集合の同値や「common起点循環100」を主張しない。
4. getDirectDependencies(modules).uniqueModulesから全辺を取りSCCを別計算する。公式ApplicationModuleは全classesのgetDirectDependenciesFromSelfを含み、type未指定は全依存種別。ModulithのSlice循環はOPENを除外するため、本番OPENなしもassertした。完全graphのSCC不参加をwebhook循環不参加の主根拠とする。
5. TransitionalBridge注釈クラスだけをignored predicateにする。bridgeだけの循環が消え、別のcommon辺の循環が残る双方をverifyする。common全体を例外化しない。

通常084と最終pin afb8は、全本番class集合のcompiled完全一致assert・同じmain出力のUP-TO-DATE再利用・同一main source treeを確認した。non-cycle message集合23,591件、raw origin map、完全SCC構成は機械比較で完全一致。最終pinでmodule内重複0/module間12、common raw/unique875/875・webhook4/4を確認した。比較範囲はdependency集合とSCCで、上限付きcycle報告message集合は含まない。

小さなsummary2枚、試験XML5本（合計15,753bytes）、比較結果とXML SHA256を `CMP-261021-1602-results.json` に保全。dependency集合hashはOrdinal順・LF区切り・UTF-8のSHA256。全体class/module数、全origin件数、全raw違反、SCC集合は各runの `docs/evidence/CMP-261021-1602-modulith-spike-structure.txt` に保全。docs PRにはこの要約だけを載せる。

## Webhookの観測限界

既存WebhookAuthzContractITはSpringBootTest基底を持つため単純にApplicationModuleTestを重ねず、新しい検体を使用。4Service/Repositoryに加え実AccessControlServiceがmockでないことを成功時にassertするが、今回そのassertへは到達していない。外部Redisだけmockし、DBと自前認可はmockしない。

5f8bの両modeはprepareTestInstance時点cache size0/hit0/miss0/failure0。その後の起動失敗がbeforeTestClass以前なので、この回の最終cache値は未取得。初期logging propertiesへDEBUGを明示した最終084では両modeともsize0/hit0/miss1/failure1を実測。各独立JVMで起動試行1・成功context0・失敗1。ALL_DEPENDENCIES modeは未測定。

独立JVMごとの試行/成功cache/失敗数を全既存suiteのcontext総数へ外挿しない。既存SpringBootTestとmodule専用contextではModuleContextCustomizer等のcache keyが異なるため、同じcontextの再利用は期待できない（公式ModuleContextCustomizerFactoryはApplicationModuleTestなしならnull、ありならModuleContextCustomizerを返し、そのequalsはexecutionを比較する）。全既存suiteの前後context総数・速度改善は**未測定**。

## 環境・再現

- Windows metadata: Temurin21.0.10+7、Gradle8.14.4、既存Windows Gradle cache。既定core1.4.2、pin4artifact1.3.0、全Modulith1.4.13のdependencyInsight成功ログは本ファイル同prefixのdependency-default/modulith/pin-resolved.log。
- runtime: WSL Ubuntu24.04.4、OpenJDK21.0.12.1+1-1-24.04.4-Ubuntu、Docker Engine29.4.1、MySQL8.0 Testcontainers。GRADLE_USER_HOME未設定、既定/home/kenta/.gradleはLinux FS。projectcacheは/tmp/mannschaft-modulith-4022-native-gradle。cache削除なし。
- git fetch/merge origin/mainで追従しrebaseなし。親Issue4022でL0-M予約なしを確認。重ビルドはすべて**外側Windows Git Bashのturnstile**経由。WSL別lockrootで直列化を破らない。
- 最初の/mnt/c実行はファイルhash読取が長期化。自己JVMの1回dumpでFileInputStream→DefaultStreamHasher→DirectorySnapshotterを確認し、自己clientへSIGINT、終了/gate解放を確認した。停止とIO要因推論を区別。他者process/lockは触っていない。
- native最初のsnapshotは3175 fullSHAからgit archive backendの**binary pipe**で新規/tmpへ52秒/138M。以降は実行が終わってからcommit差分だけ更新しSHA marker照合。main tree f20686366e68a714fcaeeeb278a38c8cabe4c075は同一、compileJavaはUP-TO-DATEを再利用。実行中snapshotは変更しない。

再現例（Windows Git Bash、spike worktree。CODE_SHAは上の対象runを指定）:

```bash
CODE_SHA=afb850b6f7719d24d142a80f6b159b3d7786be0a
SNAPSHOT=/tmp/mannschaft-modulith-repro-$CODE_SHA
MSYS_NO_PATHCONV=1 wsl.exe -d Ubuntu-24.04 -- mkdir -p "$SNAPSHOT/docs/evidence"
git archive --format=tar "$CODE_SHA" backend | MSYS_NO_PATHCONV=1 wsl.exe -d Ubuntu-24.04 -- tar -xf - -C "$SNAPSHOT"
cd backend
MSYS_NO_PATHCONV=1 bash scripts/gradle-turnstile.sh wsl.exe -d Ubuntu-24.04 --cd "$SNAPSHOT/backend" -- bash ./gradlew --project-cache-dir /tmp/mannschaft-modulith-repro-gradle modulithStructureSpike -Pspike.archunitPin=true --console=plain
# 通常版はpin propertyを外す。各専用task: modulithRegistrySpike / modulithRegistryDeleteSpike /
# modulithStructureSpike / modulithWebhookSpike / modulithWebhookDirectSpike。--continueで個別結果を回収できる。
```

CLIでのテスト名絞り込みは使用しない。metadataの再現はbackendで `./gradlew dependencyInsight --configuration spikeRuntimeClasspath --dependency com.tngtech.archunit`、pin時は単一引数 `-Pspike.archunitPin=true` を付ける。PowerShellではこのdotted propertyを引用する。

## 公式根拠

- [1.4互換表/設定](https://docs.spring.io/spring-modulith/reference/1.4/appendix.html)、[イベント/完了mode](https://docs.spring.io/spring-modulith/reference/1.4/events.html)
- [Registry公開拡張点1.4.13](https://github.com/spring-projects/spring-modulith/blob/1.4.13/spring-modulith-events/spring-modulith-events-core/src/main/java/org/springframework/modulith/events/core/DefaultEventPublicationRegistry.java)
- [Registry注入構成1.4.13](https://github.com/spring-projects/spring-modulith/blob/1.4.13/spring-modulith-events/spring-modulith-events-core/src/main/java/org/springframework/modulith/events/config/EventPublicationConfiguration.java)
- [JPA Entity1.4.13](https://github.com/spring-projects/spring-modulith/blob/1.4.13/spring-modulith-events/spring-modulith-events-jpa/src/main/java/org/springframework/modulith/events/jpa/JpaEventPublication.java)
- [ApplicationModules1.4.13](https://github.com/spring-projects/spring-modulith/blob/1.4.13/spring-modulith-core/src/main/java/org/springframework/modulith/core/ApplicationModules.java)、[ApplicationModule](https://github.com/spring-projects/spring-modulith/blob/1.4.13/spring-modulith-core/src/main/java/org/springframework/modulith/core/ApplicationModule.java)、[Violations](https://github.com/spring-projects/spring-modulith/blob/1.4.13/spring-modulith-core/src/main/java/org/springframework/modulith/core/Violations.java)

- [ModuleContextCustomizerFactory1.4.13](https://github.com/spring-projects/spring-modulith/blob/1.4.13/spring-modulith-test/src/main/java/org/springframework/modulith/test/ModuleContextCustomizerFactory.java)、[Spring context cache key](https://docs.spring.io/spring-framework/reference/6.2/testing/testcontext-framework/ctx-management/caching.html)
- [ArchUnit1.4.2 CycleConfiguration](https://github.com/TNG/ArchUnit/blob/v1.4.2/archunit/src/main/java/com/tngtech/archunit/library/cycle_detection/CycleConfiguration.java): cycles.maxNumberToDetectの既定100、到達後探索停止

配布jar/sourceは公式Maven Centralの1.4.13を取得しjavap/sourceで照合した。PowerShell Invoke-WebRequestの4KB停止はcurl取得へ切り替え、正規Gradle依存解決を実行している。依存不整合と取得手段/テスト検体不備を混同していない。
