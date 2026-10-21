# CMP-261021-1602 L0-S Modulith スパイク調査チェックポイント

## 状態

2026-10-21。**Draft（未完了）**。Registry初回baseline `0809ba747aaf9aab5bab1282ad912dde0790f52a` は4件中2成功・2失敗、その後の `2a822a4eb7878a7b3ce18ff8a44643d34ff3a6c0` は4/4成功。`5f8bac8a71a27cd3ecc4eb2c6f9a70fde75f64f4` ではDELETE 1/1、Structure 4/4成功、Webhook STANDALONE/DIRECTは各1失敗。Structureのunique集合最終照合、AC-1/2/3/4等、ArchUnit pin実行と最終cache統計は未完了。8項目の最終検証は未完了。§11.2の実DDLは確定したが、これは全L0-SやPhase 0合格を意味しない。Draftを維持する。

Spring Boot 3.5.13 向けの Modulith 1.4.13 が候補。実測DDLを docs/architecture §11.2 に反映済み（計画全体はDraft）。対象: `3bad46f846cd094a64bd46524aed69479b0c76bf`。ブランチ: `spike/cmp-261021-0017-modulith-261021`。worktree: `.claude/worktrees/agent-4022-modulith-spike`。コードを main へマージしない。

## 確認項目の現在地

| 項目 | 調査結果 | 実検証 |
|---|---|---|
| 1 Boot 互換版 | Spring Boot 3.5.13 向けの Modulith 1.4.13 が候補。公式互換表で 1.4 は Boot 3.5 向け。1.4 公式文書の例と API は 1.4.13 | 全Modulith artifactが1.4.13へ解決することを実確認。起動未実行 |
| 2 JPA Registry DDL | 実 MySQL `event_publication` は6列。`id binary(16)` が主キー、`publication_date datetime(6) NOT NULL`、`completion_date datetime(6) NULL`、`event_type/listener_id/serialized_event varchar(255) NOT NULL`。索引はPKのみ、`utf8mb4_0900_ai_ci`。attempt/state列なし | schemaとSHOW INDEXを取得し、実測基準DDLを§11.2へ反映済み。VARCHAR(255)の実制約をL1-Mのserialized length確認材料とし、TEXT/512等への変更は未確定 |
| 3 登録対象と完了 | 初回baselineで認証イベントexpected 0 / actual 1、対象listener限定expected [module-success] / actual [module-success, ordinary-reliable] の2件がred。filter適用後Registry run `2a822a4eb7878a7b3ce18ff8a44643d34ff3a6c0` は4/4 green（suite 1.752s）。非TX非同期正常完了を含む。 | DELETEの別試験は公開API直呼びで完了前1行→markCompleted後0行（1/1、suite 2.510s）。このDELETE試験はlistener flowの検証ではない。UPDATE/ARCHIVEは公式資料のみ。 |
| 4 ArchUnit | 既定1.4.2と1.3.0 pinの依存解決は成功。Structure run 4/4 green | 専用pin実行と最終cache統計は未実施。全凍結番人のgreenは未確認 |
| 5 全本番違反 | Structure run `5f8bac8a71a27cd3ecc4eb2c6f9a70fde75f64f4` は4/4 green。class集合は12396/12396一致、120 modules。global報告23691、origin依存23603、common 875 / webhook 4 | origin/global差12はdedup確認済み。unique集合照合は次run未完、AC5/6最終greenは保留 |
| 6 モジュール抽出 | 同Structure runでorigin別依存・reported cycles・SCCを出力。reported cycles 100は上限付き参考値、unknown 0、完全SCC 1（common含む、webhookなし） | 参加集合のunique照合が未完。AC5/6を最終合格扱いしない |
| 7 jobmatching/chat | Structureのjobmatching→chat.apiと自身のlistenerの検体はgreen | 全8項目とAC全体の完了を意味しない |
| 8 webhook 起動 | STANDALONE/DIRECTは各1 fail。standaloneはPasswordEncoder不在、directは実AccessControlService→UserRoleRepository依存不在。認可mockなし | rootSuite 103080/98027ms。suite context総数は未測定 |

## 残りの試験計画と未実施項目

- backend の専用 spike sourceSet と専用 Test task で検体だけを実行し、既存凍結番人を走らせない。CLI `--tests` は使用しない。
- 実 MySQL Testcontainers と実発行 commit、専用 module-event-pool、tx を持たない ModuleEventListener を使う。
- baseline実行で認証イベントの0件assertは1件保存されてred、対象listener限定assertはordinary-reliableも混入してredとなることをJUnit XMLで確認した。schema取得と非TX非同期例外後の未完了assertはgreen。
- filter候補snapshot `599c3943330c6a57c26f349f2127c6ef4d19a634` は `@Primary` の `DefaultEventPublicationRegistry` 差し替えと公開 `store` override を使い、`ReliableEvent` と実 `@ModuleEventListener` のmerged listener ID双方で保存対象を限定する。fixtureは `Listeners.class` 固定。本番全bean探索の完成品ではないが、後続Registry実測 `2a822a4eb7878a7b3ce18ff8a44643d34ff3a6c0` は4/4 green。
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
- 初回Registry baselineのred/greenと実DDLは記録済み。後続Registry 4/4およびStructure 4/4は成功したが、DELETEの別経路、Webhookの2失敗、Structure unique集合照合、AC全体は未完了。
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
# 初回baselineの記録。filter適用後Registryの後続green実測は下記を参照。
./scripts/gradle-turnstile.sh wsl.exe -d Ubuntu-24.04 -- bash scripts/modulith-spike-wsl.sh modulithRegistrySpike
```
## Registry実MySQL baseline実測（2026-10-21）

- 実行snapshot SHA: `0809ba747aaf9aab5bab1282ad912dde0790f52a`（snapshot内 `EXECUTION_SHA` と一致）。Windows外側turnstileから、未編集git archive snapshotをLinux `/tmp` に配置して実行。WSL Ubuntu 24.04、JDK 21.0.12.1、Docker Engine 29.4.1。証跡はspike worktreeの `backend/build/spike-evidence/native-registry-red-0809/` にあるJUnit XML、schema、log。
- JUnit XMLは4件中成功2・失敗2・errors 0・skip 0。XML suite timeは2.758秒、Gradle外側の壁時間は53.90秒。両者を混同しない。
- 成功: 公式Entityから生成した実MySQL schema・索引のassert、非TX非同期リスナー例外後に未完了で残るassert。
- 失敗: 認証イベント保存数assertはexpected 0 / actual 1。対象listener限定assertはexpected `[module-success]` / actual `[module-success, ordinary-reliable]`。baselineの実redであり、AC合格ではない。
- 初回baselineのDDL観測は後続runと一致し、6列・PRIMARY KEYのみ。実測基準DDLを§11.2へ反映済み。attempt/state列はない。VARCHAR(255)はL1-Mでserialized lengthを確認する材料とし、TEXT/512等の拡張DDLは未確定。
- filter候補snapshotは `599c3943330c6a57c26f349f2127c6ef4d19a634`。`@Primary DefaultEventPublicationRegistry` と公開 `store` overrideで、`ReliableEvent` と実 `@ModuleEventListener` のmerged listener IDが一致する対象だけを保存する。fixtureは `Listeners.class` 固定で、本番全bean探索の完成品ではない。後続Registry実測は4/4 greenだが、本番全bean探索は未検証。
- 初回Registry baselineはfilter未適用のred。後続の2a82 runではfilter適用後のRegistry suiteが4/4 greenとなった。ただし固定fixtureは本番全bean探索の完成品ではなく、Structure/Webhookの未完了条件も別途残る。
## 最新native runtime実測

- Registry snapshot `2a822a4eb7878a7b3ce18ff8a44643d34ff3a6c0`: JUnit 4/4成功、errors 0、skip 0、suite 1.752秒。filter後の認証イベント非保存・対象listener限定・非TX非同期正常完了等を確認。初回baseline `0809ba...` の2 redは履歴として残す。
- DELETE run（snapshot `5f8bac8a71a27cd3ecc4eb2c6f9a70fde75f64f4`）は1/1成功、suite 2.510秒、rootSuite 44262ms。`spring.modulith.events.completion-mode=delete` で、1行から公開APIの `markCompleted` を直接呼ぶと0行になった。これは非同期listener flowの実測ではない。別のRegistry 4/4で非TX async正常完了を確認。UPDATE/ARCHIVEは公式資料の説明のみ。
- 同じrunの実MySQL schemaは初回と一致し6列、PKのみ。DDL本体は§11.2へ記載。`ENGINE=InnoDB`、charset/collationはこのMySQL環境のSHOW CREATE結果であり、Modulithが宣言した値とは主張しない。attempt/state列なし。
- Structure run: JUnit 4/4成功、suite 89.807秒、rootSuite 92898ms。compiled/imported class 12396/12396一致、120 modules、all 23691、dependencyTotal 23603、common origin 875 / webhook 4。origin合計とglobalの差12は公式dedup挙動を確認済み。reportedCycles 100は上限付き参考値、unknown 0、完全SCC 1（common含む、webhookなし）。unique集合照合が未完なのでAC-5/6最終greenは保留。
- Webhook STANDALONE/DIRECT は各1 failure。rootSuiteは103080ms / 98027ms。前者はPasswordEncoder不在、後者は実AccessControlServiceからUserRoleRepositoryへの依存不在。認可mockなし。suite context総数は未測定。
- ArchUnit pin専用実行と最終cache統計は未実施。8項目全体、AC、L0-S/Phase 0は未完了のDraftである。
