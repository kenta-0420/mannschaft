# CMP-261021-1602 L0-S Modulith スパイク調査チェックポイント

## 状態

2026-10-21。**Draft（未完了）**。台帳登録 PR #4027 は MERGED。main commit `7e9f82b3cae1` で台帳登録が実装中の状態を確認済み。試練 checkpoint `78960ac2aa` 作成済み。依存 metadata の版確認は成功。初回 Registry runtime 実測（snapshot `0809ba747aaf9aab5bab1282ad912dde0790f52a`）は4件中成功2・失敗2・errors 0・skip 0。これは保存対象filter未適用のbaselineであり、filter後のgreen実測ではない。8項目全体の最終実測とAC-1/2/3/4等は未完了。実DDLは取得済みだが docs/architecture §11.2 は未確定。L0-S合格やPhase 0完了とは扱わない。

Spring Boot 3.5.13 向けの Modulith 1.4.13 が候補。DDL の docs/architecture §11.2 は未確定。対象: `3bad46f846cd094a64bd46524aed69479b0c76bf`。ブランチ: `spike/cmp-261021-0017-modulith-261021`。worktree: `.claude/worktrees/agent-4022-modulith-spike`。コードを main へマージしない。

## 確認項目の現在地

| 項目 | 調査結果 | 実検証 |
|---|---|---|
| 1 Boot 互換版 | Spring Boot 3.5.13 向けの Modulith 1.4.13 が候補。公式互換表で 1.4 は Boot 3.5 向け。1.4 公式文書の例と API は 1.4.13 | 全Modulith artifactが1.4.13へ解決することを実確認。起動未実行 |
| 2 JPA Registry DDL | 実 MySQL `event_publication` は6列。`id binary(16)` が主キー、`publication_date datetime(6) NOT NULL`、`completion_date datetime(6) NULL`、`event_type/listener_id/serialized_event varchar(255) NOT NULL`。索引はPKのみ、`utf8mb4_0900_ai_ci`。attempt/state列なし | schemaとSHOW INDEXを取得済み。観測値は§11.2の確定仕様やTEXT長への変更を承認しない |
| 3 登録対象と完了 | baselineの標準Registryでは既存認証イベント非保存assertが expected 0 / actual 1 でred。対象listener限定assertも expected `[module-success]` / actual `[module-success, ordinary-reliable]` でred。非TX非同期例外後の未完了assertはgreen | 4件のうち上記2red・schema/例外後未完了の2green。filter後の成功保存・完了処理は未実測 |
| 4 ArchUnit | 公式 1.4 GA 発表は ArchUnit 1.4 を含む。既存は1.3.0 | spikeRuntimeClasspath本体は既定1.4.2。core/junit5-api/engine/engine-apiを1.3.0にforceした依存解決は成功。解析実行・全凍結番人は未実行 |
| 5 全本番違反 | ApplicationModules で全トップレベルパッケージを個別に検査する | 全本番コンパイル・依存違反/循環/common発集計未実行 |
| 6 モジュール抽出 | 依存は発生元、循環は参加モジュール全てで判定。TransitionalBridge 注釈クラスだけを ApplicationModules の ignored predicate から除外する最小陽性/陰性検体を予定 | 公開API確定・webhook件数・検体未実行 |
| 7 jobmatching/chat | jobmatching→chat.api のみ、イベントと保険リスナーは jobmatching 自身に置く検体を予定 | 循環0の実証未実行 |
| 8 webhook 起動 | 既存 WebhookAuthzContractIT は AbstractMySqlIntegrationTest の SpringBootTest を継承。ApplicationModuleTest を単純追加しない。webhookの4Serviceは具象 AccessControlService 依存 | 独立起動可否・時間・context増分未測定 |

## 残りの試験計画と未実施項目

- backend の専用 spike sourceSet と専用 Test task で検体だけを実行し、既存凍結番人を走らせない。CLI `--tests` は使用しない。
- 実 MySQL Testcontainers と実発行 commit、専用 module-event-pool、tx を持たない ModuleEventListener を使う。
- baseline実行で認証イベントの0件assertは1件保存されてred、対象listener限定assertはordinary-reliableも混入してredとなることをJUnit XMLで確認した。schema取得と非TX非同期例外後の未完了assertはgreen。
- filter候補snapshot `599c3943330c6a57c26f349f2127c6ef4d19a634` は `@Primary` の `DefaultEventPublicationRegistry` 差し替えと公開 `store` override を使い、`ReliableEvent` と実 `@ModuleEventListener` のmerged listener IDの両方で保存対象を限定する。fixtureは `Listeners.class` 固定で、本番全beanの探索完成品ではない。filter後のgreenと完了処理はこれから実測する。
- 構造検査は Spring context と別 JVM へ分離する。文字列だけの曖昧な分類は採用せず、採用版の公開 API と実出力を照合して発生元・循環参加集合の抽出手順を確定する。
- 重ビルド直前に親 Issue #4022 の計測窓と最新宣言を再確認し、backend/scripts/gradle-turnstile.sh を通す。

## 確認済み環境・再現操作

- Windows JDK: Temurin 21.0.10+7。Git Bash から java 起動可。
- backend/ と docs/architecture、docs/security、docs/evidence の cone sparse checkout を実施。再開時 `git merge origin/main` は Already up to date。
- 親 Issue の読取時点（2026-10-21 16時台 JST）では L0-M 未着手、計測窓予約なし。これは将来の実行許可を意味しない。
- 通常 exec shell は親から使用不能と引き継ぎ。必要最小コマンドを sandbox 外で実行。PowerShell ファイル読込と出力は UTF-8 を指定する。
- 公式 Maven Central は HTTP 200。curlでcore/events-jpa/test/events-coreの1.4.13 jarを取得し、javapで公開APIとEntity注釈を照合した。Gradle dependency metadata解決も成功済み（下記ログ参照）。依存解決の成功はruntime実測やAC全体の合格を意味しない。キャッシュ削除なし。

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
- Registry baselineのred/green内訳と実DDLは取得済み（下記0809実測）。red 2件を既知の失敗として保持し、filter後green・構造検査・残りのACを未完了とする。
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
# Windows outer turnstileからWSL Linuxの未編集git archive snapshotへ実行済み。filter後greenは未実施
./scripts/gradle-turnstile.sh wsl.exe -d Ubuntu-24.04 -- bash scripts/modulith-spike-wsl.sh modulithRegistrySpike
```
## Registry実MySQL baseline実測（2026-10-21）

- 実行snapshot SHA: `0809ba747aaf9aab5bab1282ad912dde0790f52a`（snapshot内 `EXECUTION_SHA` と一致）。Windows外側turnstileから、未編集git archive snapshotをLinux `/tmp` に配置して実行。WSL Ubuntu 24.04、JDK 21.0.12.1、Docker Engine 29.4.1。証跡はspike worktreeの `backend/build/spike-evidence/native-registry-red-0809/` にあるJUnit XML、schema、log。
- JUnit XMLは4件中成功2・失敗2・errors 0・skip 0。XML suite timeは2.758秒、Gradle外側の壁時間は53.90秒。両者を混同しない。
- 成功: 公式Entityから生成した実MySQL schema・索引のassert、非TX非同期リスナー例外後に未完了で残るassert。
- 失敗: 認証イベント保存数assertはexpected 0 / actual 1。対象listener限定assertはexpected `[module-success]` / actual `[module-success, ordinary-reliable]`。baselineの実redであり、AC合格ではない。
- DDL `event_publication` は6列: `completion_date datetime(6) NULL`、`publication_date datetime(6) NOT NULL`、`id binary(16) NOT NULL`（PRIMARY KEY）、`event_type varchar(255) NOT NULL`、`listener_id varchar(255) NOT NULL`、`serialized_event varchar(255) NOT NULL`。索引はPRIMARY KEYのみ、照合順序は `utf8mb4_0900_ai_ci`。attempt/state列なし。§11.2は今回変更せず、TEXT512等の暫定値を導入しない。
- filter候補snapshotは `599c3943330c6a57c26f349f2127c6ef4d19a634`。`@Primary DefaultEventPublicationRegistry` と公開 `store` overrideで、`ReliableEvent` と実 `@ModuleEventListener` のmerged listener IDが一致する対象だけを保存する。fixtureは `Listeners.class` 固定で、本番全bean探索の完成品ではない。filter後greenと完了処理は未実測。
- これはRegistryの部分実測。8項目の最終検証とAC-1/2/3/4等は未完了で、L0-S/Phase 0合格としない。Draftを維持する。
