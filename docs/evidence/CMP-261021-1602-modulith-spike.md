# CMP-261021-1602 L0-S Modulith スパイク調査チェックポイント

## 状態

2026-10-21。**試練作成済み、実検証は turnstile 待機**。PR #4027 の main 反映と台帳の実装中状態を確認し、main を merge した後に専用 sourceSet/Test タスクと検体を作成した。保存限定実装は red の実アサーション失敗確認まで加えない。現時点を L0-S 合格や Phase 0 完了とは扱わない。

対象: `3bad46f846cd094a64bd46524aed69479b0c76bf`。ブランチ: `spike/cmp-261021-0017-modulith-261021`。worktree: `.claude/worktrees/agent-4022-modulith-spike`。コードを main へマージしない。

## 確認項目の現在地

| 項目 | 調査結果 | 実検証 |
|---|---|---|
| 1 Boot 互換版 | 候補を Modulith 1.4.13 とする。公式互換表で 1.4 は Boot 3.5 向け。1.4 公式文書の例と API は 1.4.13 | 全Modulith artifactが1.4.13へ解決することを実確認。起動未実行 |
| 2 JPA Registry DDL | 公式 1.4.13 Entity は id、publicationDate、listenerId、serializedEvent、eventType、completionDate。DefaultJpaEventPublication の表名は EVENT_PUBLICATION。試行回数・処理状態列と Entity の索引指定は見当たらない。completion-mode は update/delete/archive | 実 MySQL の SHOW CREATE TABLE・索引照合未実行。§11.2 の型・長さ・索引は確定不可 |
| 3 登録対象と完了 | 標準 multicaster は AFTER_COMMIT リスナー配信全般を保存。public/nonfinal DefaultEventPublicationRegistry の store(Object, Stream<PublicationTargetIdentifier>) を差し替え、ReliableEvent と ModuleEventListener の listener ID の両方を照合する検体を予定。markCompleted は REQUIRES_NEW | 既存 UserRegisteredEvent 非保存の red/green、tx 無し async 正常完了・例外未完了とも未実行 |
| 4 ArchUnit | 公式 1.4 GA 発表は ArchUnit 1.4 を含む。既存は1.3.0 | spikeRuntimeClasspath本体は既定1.4.2。core/junit5-api/engine/engine-apiを1.3.0にforceした依存解決は成功。解析実行・全凍結番人は未実行 |
| 5 全本番違反 | ApplicationModules で全トップレベルパッケージを個別に検査する | 全本番コンパイル・依存違反/循環/common発集計未実行 |
| 6 モジュール抽出 | 依存は発生元、循環は参加モジュール全てで判定。TransitionalBridge 注釈クラスだけを ApplicationModules の ignored predicate から除外する最小陽性/陰性検体を予定 | 公開API確定・webhook件数・検体未実行 |
| 7 jobmatching/chat | jobmatching→chat.api のみ、イベントと保険リスナーは jobmatching 自身に置く検体を予定 | 循環0の実証未実行 |
| 8 webhook 起動 | 既存 WebhookAuthzContractIT は AbstractMySqlIntegrationTest の SpringBootTest を継承。ApplicationModuleTest を単純追加しない。webhookの4Serviceは具象 AccessControlService 依存 | 独立起動可否・時間・context増分未測定 |

## 試験の進め方（未実行）

- backend の専用 spike sourceSet と専用 Test task で検体だけを実行し、既存凍結番人を走らせない。CLI `--tests` は使用しない。
- 実 MySQL Testcontainers と実発行 commit、専用 module-event-pool、tx を持たない ModuleEventListener を使う。
- 認証イベント保存0件のアサーションを先に作り、標準 Registry で保存されて red になることを結果 XML で確認してから保存限定実装を加える。
- ReliableEvent に通常リスナーと対象リスナーを併設し、対象 listener のみ保存する。成功では完了日時を確認し、別ケースで delete モードの完了即削除を確認する。例外ケースは未完了行を確認する。
- 構造検査は Spring context と別 JVM へ分離する。文字列だけの曖昧な分類は採用せず、採用版の公開 API と実出力を照合して発生元・循環参加集合の抽出手順を確定する。
- 重ビルド直前に親 Issue #4022 の計測窓と最新宣言を再確認し、backend/scripts/gradle-turnstile.sh を通す。

## 確認済み環境・再現操作

- Windows JDK: Temurin 21.0.10+7。Git Bash から java 起動可。
- backend/ と docs/architecture、docs/security、docs/evidence の cone sparse checkout を実施。再開時 `git merge origin/main` は Already up to date。
- 親 Issue の読取時点（2026-10-21 16時台 JST）では L0-M 未着手、計測窓予約なし。これは将来の実行許可を意味しない。
- 通常 exec shell は親から使用不能と引き継ぎ。必要最小コマンドを sandbox 外で実行。PowerShell ファイル読込と出力は UTF-8 を指定する。
- 公式 Maven Central は HTTP 200 を返した。PowerShell Invoke-WebRequest が4KBで停止した取得は中断し、curl で core/events-jpa/test/events-core の 1.4.13 jar を取得、javap で公開 API と Entity 注釈を照合した。Gradle の正規依存解決はまだ実行待ち。キャッシュ削除は行っていない。

```powershell
git rev-parse HEAD
git status --short
git merge origin/main
gh issue view 4022 --repo kenta-0420/mannschaft --json body,comments
```

## 公式根拠

- [1.4 互換表と設定一覧](https://docs.spring.io/spring-modulith/reference/1.4/appendix.html)
- [1.4 イベント・完了モード](https://docs.spring.io/spring-modulith/reference/1.4/events.html)
- [1.4 GA と ArchUnit 更新](https://spring.io/blog/2025/05/28/spring-modulith-1-4-1-3-6-and-1-2-13-released/)
- [1.4.13 PersistentApplicationEventMulticaster](https://github.com/spring-projects/spring-modulith/blob/1.4.13/spring-modulith-events/spring-modulith-events-core/src/main/java/org/springframework/modulith/events/support/PersistentApplicationEventMulticaster.java)
- [1.4.13 DefaultEventPublicationRegistry](https://github.com/spring-projects/spring-modulith/blob/1.4.13/spring-modulith-events/spring-modulith-events-core/src/main/java/org/springframework/modulith/events/core/DefaultEventPublicationRegistry.java)
- [1.4.13 JpaEventPublication](https://github.com/spring-projects/spring-modulith/blob/1.4.13/spring-modulith-events/spring-modulith-events-jpa/src/main/java/org/springframework/modulith/events/jpa/JpaEventPublication.java)
- [1.4.13 DefaultJpaEventPublication](https://github.com/spring-projects/spring-modulith/blob/1.4.13/spring-modulith-events/spring-modulith-events-jpa/src/main/java/org/springframework/modulith/events/jpa/updating/DefaultJpaEventPublication.java)

## §11.2 への申し送り

現時点では暫定DDLを確定版へ差し替えてはならない。実 Entity の既定文字列長、MySQL dialect が Instant に割り当てる型、実索引を測り、必要なプロジェクト側の長さ拡張・未完了検索索引を公式既定と明確に分ける。試行回数/隔離状態は上記 Entity に無いため、L1-M の補助表が必要という仮説を実配布物で確定する。
## 試練作成後のチェックポイント

- 2026-10-21 18時台 JST。試練 commit `78960ac2aa`。`modulithRegistrySpike` を turnstile 経由で起動したが、他者の生存ロックにより待機している。1133秒以上の待機時点で Gradle 自体は未開始。
- RegistrySpikeIT: 認証非保存、対象listener限定と正常完了、例外後未完了、Entityからの実DDL/索引。
- StructureSpikeIT: 全本番class集合照合、発生元別依存違反/報告循環/別記SCC、jobmatching→chat.api、自身のlistener、橋渡し限定除外の陽性/陰性。
- WebhookSpikeIT: 独立 ApplicationModuleTest。4ServiceとRepositoryを実ロード。失敗する場合も隠さず起動失敗として記録する。
- 同じ専用 Test タスクが既存の archunit タグ除外を継承して空実行しないよう、専用タスク内だけタグ集合を明示解除した。既存番人タスクの設定は変更していない。
- 保持者は MSYS PID190676、WINPID48628、CREATED20261021065352。読取の ps -ef で MSYS 子190784（別 worktree の Gradle wrapper）を確認。保持者・子・ロックを停止/解除していない。
- 実行の red/green、DDL、依存解決、違反件数、webhook起動結果のログは未取得。待機ログだけを合格根拠にしない。
## 依存解決と実行環境の実測

対象 SHA `90b2b55e08401233a034168d4782631ba5db0ea7`（依存設定は試練commit以来同じ）。依存解決は Windows Temurin21.0.10、Gradle8.14.4、既存 Windows user cache。実コード試験は Windows に Docker CLI/標準DockerDesktopが無いため、既存 WSL Ubuntu-24.04（JDK21.0.12.1、Docker Engine29.4.1、既存Linux Gradle cache）へ差し替えた。Engine到達性は docker info で確認。TCP公開などの環境設定変更なし。

| コマンドの目的 | 結果 | ログ |
|---|---|---|
| Modulith同居のArchUnit既定版 | core1.4.2へ競合解決。BUILD SUCCESSFUL、4分25秒 | `CMP-261021-1602-modulith-dependency-default.log` |
| 全Modulithの版 | api/apt/core/docs/events-api/core/jackson/jpa/moments/starter-core/jpa/test/testが1.4.13。既存AWS BOMの1.3.1要求も1.4.13へ解決。BUILD SUCCESSFUL、44秒 | `CMP-261021-1602-modulith-dependency-modulith.log` |
| ArchUnit1.3.0固定 | core/junit5-api/engine/engine-apiのforce解決成功。BUILD SUCCESSFUL、1分10秒 | `CMP-261021-1602-modulith-dependency-pin-resolved.log` |

```powershell
# backend/。metadataのみ、compile/testは起動しない
.\gradlew.bat dependencyInsight --configuration spikeRuntimeClasspath --dependency com.tngtech.archunit --console=plain
.\gradlew.bat dependencyInsight --configuration spikeRuntimeClasspath --dependency org.springframework.modulith --console=plain
.\gradlew.bat dependencyInsight --configuration spikeRuntimeClasspath --dependency com.tngtech.archunit '-Pspike.archunitPin=true' --console=plain
```

最初の pin 呼出は PowerShell に未引用の dotted property を分割され、Task .archunitPin=true not found で失敗した。これは依存互換性の失敗ではない。一引数へ引用した再実行が上記の成功結果。1.3.0へ解決できたことは Modulith実解析の動作成功や既存freeze全体の同値を証明しない。後続の実解析と導入時CIを別に要する。

旧Windows redはGradle未開始の待機中に自分のscriptだけを終了し、Windows turnstileの外側でWSLを呼ぶ形へ差し替えた。他者保持者には触れていない。WSLが独自のlockroot/PID namespaceでWindows保持者をstale扱いする経路は使用しない。既存cache削除なし。WSLのproject cacheだけを `/tmp/mannschaft-modulith-4022-gradle` に隔離する。gradlewのCR文字0も確認済み。

```bash
# Windows Git Bash、backend/。実試験はまだturnstile待機
./scripts/gradle-turnstile.sh wsl.exe -d Ubuntu-24.04 -- bash scripts/modulith-spike-wsl.sh modulithRegistrySpike
```