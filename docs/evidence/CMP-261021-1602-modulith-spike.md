# CMP-261021-1602 Modulith L0-S 実測結果

## 判定

2026-10-21時点の最終実測を記録したDraft PR用証拠文書。Spring Boot 3.5.13向けSpring Modulith 1.4.13で、Registry、標準/固定ArchUnit版のStructure検査、Webhook起動試験を実施した。8項目の結果は下表のとおり。Webhookの独立ApplicationModuleTestは両モードで起動失敗したため、L0-S/Phase 0の採用合格とは判定しない。失敗をgreen化せず、既存境界を改善した後の再評価材料として残す。計画書§11.2には実MySQLで観測した1.4.13基準DDLを反映した。

対象コードSHAはStructure通常版 `084279db3f7de80b1ca20ba5e917fc493d29b544`、ArchUnit pin版 `afb850b6f7719d24d142a80f6b159b3d7786be0a`。Registryのfilter候補は `599c3943330c6a57c26f349f2127c6ef4d19a634`。検証snapshotはWindows外側turnstileからWSL Ubuntu 24.04/JDK 21.0.12.1/Docker Engine 29.4.1上の未編集git archiveで実行した。Module versionは1.4.13。通常/pin構造実測でArchUnitの実JVM ImplementationVersionとCodeSourceを確認した。

## 8項目の結論

| # | 項目 | 最終結果 |
|---|---|---|
| 1 | Boot互換版 | Spring Boot 3.5.13向けのModulith 1.4.13を候補とする。全Modulith artifactの依存解決を確認した。アプリ全体の起動互換性はこの検証範囲で主張しない。 |
| 2 | JPA Registry DDL | 実MySQLで6列、PRIMARY KEYのみを確認。列型は§11.2のDDL。`utf8mb4`/`utf8mb4_0900_ai_ci`はMySQL環境のSHOW CREATE結果であり、ライブラリの宣言値とは扱わない。 |
| 3 | 保存対象と完了 | 初回baseline 2/4で、認証イベントの期待0/実際1、listener限定の期待[module-success]/実際[module-success, ordinary-reliable]が失敗。filter適用後Registry suiteは4/4成功。`spike.properties`で`spring.modulith.events.completion-mode=update`を明示し、非TX非同期正常完了で完了日が設定され行が保持されることを確認した。別contextのcompletion-mode=deleteでは公開API `markCompleted` の直接呼出し後に1行から0行となった（1/1）。 |
| 4 | ArchUnit版 | 通常版1.4.2、pin版1.3.0の両方でStructure suite 4/4成功。ImplementationVersion/CodeSourceから実JVMがロードした版を確認した。両版で非循環依存メッセージ集合、origin raw map、完全SCC構成が一致した。`EntityUuidV7ConventionArchTest`の対象packages (`com.mannschaft.app`)、`DoNotIncludeTests`と公式Entity FQCNの静的照合でD-2b対象外と確認した（番人の再実行ではない）。 |
| 5 | 全本番class/依存 | compiled/imported class 12,396/12,396、120 modules。global報告23,691件はunique非循環依存23,591件と上限付き循環報告100件。依存メッセージ完全一致のSHA-256は `73d044f67af6aa2d0d205d8f4d1f0507c873209c174e1b232905d30d087c9d52`。origin合計23,603、module内重複0、module間重複12。全root/module baseから抽出した集合をcompiled class集合と完全一致させ、OPEN moduleなしも確認した。 |
| 6 | origin/SCC抽出 | `module.detectDependencies(modules).getMessages().distinct()`でorigin別依存を抽出。commonはraw/uniqueとも875、webhookは4。循環は完全directGraphのSCCで判定し、SCCは1つ（commonを含みwebhookを含まない）。ArchUnit `cycles.maxNumberToDetect`既定上限100に到達するため、reported cycle数は参考値。通常/pinでcommon参加報告は100/99、webhookは双方0だが、版間のcycleメッセージ集合一致とは主張しない。 |
| 7 | jobmatching/chat | Structure suiteでjobmatching→chat.apiと自身のlistenerの検体を確認。対象経路は成功し、全class集合・module集合照合も通過した。 |
| 8 | Webhook起動 | STANDALONE/DIRECT_DEPENDENCIESは各1件失敗。STANDALONEは`PasswordEncoder` bean不在、DIRECTは実`AccessControlService`から必要な`UserRoleRepository` bean不在。認可mockなし（Redis外部mockのみ）。両独立JVMのcacheはsize/hit/miss/failureが0/0/1/1。[`ModuleContextCustomizerFactory`](https://github.com/spring-projects/spring-modulith/blob/1.4.13/spring-modulith-test/src/main/java/org/springframework/modulith/test/ModuleContextCustomizerFactory.java)はModuleTestなしでnull、ありでcustomizerを返し、equalsはexecution比較を行う。既存@SpringBootTestとは異なるcache keyになる根拠を確認した。全既存suiteの前後を通じたcontext総数は未測定。 |

Webhookの失敗は、現在の構成で当該ApplicationModuleTest contextが起動できないという実測結果である。所要時間はSTANDALONE rootSuite 89,971ms、DIRECT 99,141ms。§14の「Modulith 1/3/4は不可」とする保留条件とは別の評価軸として記録し、実測から禁止条件を拡張しない。

## Registry詳細

初回baseline snapshot `0809ba747aaf9aab5bab1282ad912dde0790f52a` は4件中2成功/2失敗、errors 0、skip 0、suite 2.758秒。後続filter適用snapshot `2a822a4eb7878a7b3ce18ff8a44643d34ff3a6c0` は4/4成功、errors 0、skip 0、suite 1.752秒。filterは`@Primary DefaultEventPublicationRegistry`と公開`store` overrideを用い、`ReliableEvent`かつ実`@ModuleEventListener`のmerged listener ID一致で保存対象を限定する。fixtureは`Listeners.class`固定で、本番全bean探索の完成品ではない。

DELETE snapshot `5f8bac8a71a27cd3ecc4eb2c6f9a70fde75f64f4`は1/1成功、suite 2.510秒。これはlistener flowではなく、delete設定の別contextで公開APIを直接呼ぶ実験である。UPDATEは明示設定した非TX非同期完了で実測済み。未指定時のdefault値は公式source確認のみ。設定のhot切替とARCHIVE設定は未測定。

## 証拠ファイル

以下は検証用throwaway branchの最終固定commit `df3ff645be0a08bd3fd19cd424caaeb40e55c1a6`内の保全先。リンクはこの不変SHAを参照する。

- [結果summary JSON](https://github.com/kenta-0420/mannschaft/blob/df3ff645be0a08bd3fd19cd424caaeb40e55c1a6/docs/evidence/CMP-261021-1602-results.json)
- [Registry green XML](https://github.com/kenta-0420/mannschaft/blob/df3ff645be0a08bd3fd19cd424caaeb40e55c1a6/docs/evidence/CMP-261021-1602-registry-green.xml)、[DELETE XML](https://github.com/kenta-0420/mannschaft/blob/df3ff645be0a08bd3fd19cd424caaeb40e55c1a6/docs/evidence/CMP-261021-1602-registry-delete.xml)、[baseline red XML](https://github.com/kenta-0420/mannschaft/blob/df3ff645be0a08bd3fd19cd424caaeb40e55c1a6/docs/evidence/CMP-261021-1602-registry-red.xml)
- [通常版Structure summary/XML](https://github.com/kenta-0420/mannschaft/blob/df3ff645be0a08bd3fd19cd424caaeb40e55c1a6/docs/evidence/CMP-261021-1602-structure-default.txt)、[XML](https://github.com/kenta-0420/mannschaft/blob/df3ff645be0a08bd3fd19cd424caaeb40e55c1a6/docs/evidence/CMP-261021-1602-structure-default.xml)
- [ArchUnit 1.3.0 pin summary/XML](https://github.com/kenta-0420/mannschaft/blob/df3ff645be0a08bd3fd19cd424caaeb40e55c1a6/docs/evidence/CMP-261021-1602-structure-pin.txt)、[XML](https://github.com/kenta-0420/mannschaft/blob/df3ff645be0a08bd3fd19cd424caaeb40e55c1a6/docs/evidence/CMP-261021-1602-structure-pin.xml)
- [実測schema](https://github.com/kenta-0420/mannschaft/blob/df3ff645be0a08bd3fd19cd424caaeb40e55c1a6/docs/evidence/CMP-261021-1602-modulith-spike-schema.txt)
- [Registry試験](https://github.com/kenta-0420/mannschaft/blob/df3ff645be0a08bd3fd19cd424caaeb40e55c1a6/backend/src/spike/java/com/mannschaft/spike/RegistrySpikeIT.java)、[Structure試験](https://github.com/kenta-0420/mannschaft/blob/df3ff645be0a08bd3fd19cd424caaeb40e55c1a6/backend/src/spike/java/com/mannschaft/spike/StructureSpikeIT.java)、[Webhook STANDALONE試験](https://github.com/kenta-0420/mannschaft/blob/df3ff645be0a08bd3fd19cd424caaeb40e55c1a6/backend/src/spike/java/com/mannschaft/app/webhook/WebhookSpikeIT.java)、[Webhook DIRECT試験](https://github.com/kenta-0420/mannschaft/blob/df3ff645be0a08bd3fd19cd424caaeb40e55c1a6/backend/src/spike/java/com/mannschaft/app/webhook/WebhookDirectSpikeIT.java)、[spike.properties](https://github.com/kenta-0420/mannschaft/blob/df3ff645be0a08bd3fd19cd424caaeb40e55c1a6/backend/src/spike/resources/spike.properties)、[EntityUuidV7ConventionArchTest](https://github.com/kenta-0420/mannschaft/blob/df3ff645be0a08bd3fd19cd424caaeb40e55c1a6/backend/src/test/java/com/mannschaft/app/common/architecture/EntityUuidV7ConventionArchTest.java)

最終raw XMLとJVM情報はJSON summaryにSHA-256を記録した。通常/pin Structureのsuite時間は108.286/96.789秒、rootSuiteは110,582/99,439ms。pin Gradle壁時間は104.07秒。これらはXML suite時間と別の指標である。Webhook raw XMLはローカルbuild証跡として保全し、JSON summaryには失敗原因・rootSuite時間・cache統計・XML SHA-256を記録した。

## 公式根拠

- [Spring Modulith 1.4互換表・設定](https://docs.spring.io/spring-modulith/reference/1.4/appendix.html)
- [Spring Modulith 1.4イベントと完了モード](https://docs.spring.io/spring-modulith/reference/1.4/events.html)
- [1.4.13 DefaultEventPublicationRegistry](https://github.com/spring-projects/spring-modulith/blob/1.4.13/spring-modulith-events/spring-modulith-events-core/src/main/java/org/springframework/modulith/events/core/DefaultEventPublicationRegistry.java)
- [1.4.13 JpaEventPublication](https://github.com/spring-projects/spring-modulith/blob/1.4.13/spring-modulith-events/spring-modulith-events-jpa/src/main/java/org/springframework/modulith/events/jpa/JpaEventPublication.java)
- [1.4.13 DefaultJpaEventPublication](https://github.com/spring-projects/spring-modulith/blob/1.4.13/spring-modulith-events/spring-modulith-events-jpa/src/main/java/org/springframework/modulith/events/jpa/updating/DefaultJpaEventPublication.java)
