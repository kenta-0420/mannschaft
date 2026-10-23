# CMP-261021-0017 repayment-c 公開契約準備（GDPR / admin）

本書は親 [#4022](https://github.com/kenta-0420/mannschaft/issues/4022) 内の横断契約準備であり、子CMPを新設しない。
[相手ownerへのREQ](https://github.com/kenta-0420/mannschaft/issues/4022#issuecomment-6366534200) と [repayment-cの準備宣言](https://github.com/kenta-0420/mannschaft/issues/4022#issuecomment-6368860063) に対し、現ソースの根拠、最小候補、所有、将来の検証を保全する。
**公開FQCN・署名・試験名・統合束はすべて未合意・未予約の候補であり、APIは未提供。現在は設計段階で、実装・テストコード作成・番人/ストア変更を開始しない。**

[第1波計画](CMP-261023-2301-wave1-plan.md) はknowledgebaseのみのままとする。
GDPR/admin/chartを第1波へ追加せず、各ドメイン全体の波も割り当てない。
所有者側の支援契約を将来実装する前に、波と依存契約の統合範囲・編集ownerを親Issueで確定する。
L1-F/G等の入口未充足を本書の着地で解消済みと見なさない。全体方針の正本は [境界返済計画](../architecture/modular_monolith_boundary_debt_repayment.md)。
実装入口は全L2共通のL1-G0/K/F/G完了に加え、認可を使う範囲はL1-A、C/F採用時はL1-M完了とwave/patternの証拠を必要とする。現在GDPR/adminは波未割当である。

## 1. 固定観測と回答範囲

観測SHAは `d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1`。以下のソースリンクはすべてこのSHAへ固定する。
今回行ったのはソース/既存試験/規約/計画の読取と静的参照検索だけであり、本番動作・Gradle・Docker・CI・試験XML・OpenAPI・Modulith verifyは未実測。

| 要求 | 最小回答候補 | 今回確定しないこと |
|---|---|---|
| GDPR公開イベント/完了報告 | KBが参照するイベントと完了窓口をgdpr.apiへ揃え、既存B経路とcommit後報告を維持する。 | 全domain一括移設の編集許可、C/Registryへの変換、chartの原子性変更。 |
| GDPR→KB逆辺 | 設定purge SPIをgdpr.apiが所有し、KB具体ListenerのDI/switch枝だけをSPI選択へ置換する。 | 本人設定28domainの一括SPI化、GDPRロット全体の返済完了。 |
| REQ-RPA-ADMIN-01 | 公開バッチ注釈とRegistry/Aspect/gateログ名/番人を新旧同時認識させ、その後KBの型だけを置換する。 | 他167付与行の一括置換、admin全体の移設、chart波への着手。 |

本人設定retryの集合は [VALID_DOMAINSと調停](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/gdpr/service/GdprSettingsPurgeRetryService.java#L107-L179) の28domainであり、KBだけをSPIへ出す場合も他27枝を残せる。
BatchEndpointはmain Javaの行頭直接付与169行、旧型import156ファイルを静的検索で観測した。古い注釈Javadocの153件は現数量として使わない。
169は稼働Beanの実登録数でもScheduledメソッド数でもない。KB/chart各1付与を含む全体互換の規模を示すだけで、今回の編集範囲にしない。

## 2. GDPR：KB支援の最小契約

すべて `com.mannschaft.app.gdpr.api` 所有の候補。HTTP DTO、Entity/Repository、KB業務型を公開しない。

| 現状 | 公開候補 | 所有と維持する契約 |
|---|---|---|
| gdpr.event.AccountPurgedEvent(Long,String) | `AccountPurgedEvent(Long userId)`、`Long getUserId()`、既存BaseEvent継承 | gdprが発行。userId null拒否と既存LocalDateTime occurredAtを維持。emailHash除去は全発行/購読owner確認後のみ。 |
| AccountPurgeCompletionServiceへの直接参照 | final `GdprPurgeCompletionApi` の `void markDomainSuccess(Long userId, String domainName)` | 無TX窓口から既存Service proxyへ委譲。実体のREQUIRES_NEWとbulk更新を維持。 |
| GdprSettingsPurgeRetryServiceのKB具体DI | SPI `SettingsPurgeParticipant` の `String domainName()` / `boolean retryPurge(Long userId)` | gdprがSPIを所有、KB既存Listenerが実装しdomainName=`knowledgebase`。新規汎用Registry基盤は作らない。 |

[現イベント](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/gdpr/event/AccountPurgedEvent.java#L23-L38) はuserIdとnullable emailHashを持つ。
[BaseEvent](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/common/event/BaseEvent.java#L11-L23) の時刻契約はLocalDateTimeであり、ここでInstantのrecordへ置き換えない。
現ソース検索でイベントのgetEmailHashを利用する本番購読コードは検出されなかったが、payload削減/型移設には全購読ownerの確認が要る。
[発行本体](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/gdpr/service/AccountPurgeService.java#L226-L258) はPENDING群を同じTXで登録後に発行する。completion表/監査のemailHashを消す案ではない。
旧新イベントの二重発行はしない。発行/購読型を一括で替える場合も、各owner合意済みの統合束として先に範囲を決める。

[KB Listener](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/knowledgebase/event/KbPageFavoritePurgeEventListener.java#L24-L54) の自動経路はpurge-pool / AFTER_COMMIT / REQUIRES_NEWで削除し、削除commit後だけ完了Serviceを呼ぶ。
手動retryもREQUIRES_NEWで削除しbooleanを返す。SPI実装はこのメソッドを維持し、自己呼出しでproxy/txを失う構造を追加しない。
[調停側のKB直接参照](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/gdpr/service/GdprSettingsPurgeRetryService.java#L89) と [switch枝](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/gdpr/service/GdprSettingsPurgeRetryService.java#L172-L209) をSPIへ置換し、gdpr→KB具体型の逆辺を外す。
無TX調停→owner proxyのcommit成功→完了saveの順、supports/未知domain/行なし例外/既SUCCESS skip/試行数/RetryResultResponseを維持する。

SPIはList注入から内部Mapを起動時に一度構築する候補とする。
同domain二重登録と必須knowledgebase実装の欠落は起動失敗で検出し、未登録を成功/no-op扱いしない。
28domain全てのSPI登録を必須にせず、残したswitch枝との所有重複も禁止する。

[完了Service](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/gdpr/service/AccountPurgeCompletionService.java#L30-L51) は0行更新でもWARN正常終了。
[markSuccessのSQL](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/gdpr/repository/AccountPurgeCompletionStatusRepository.java#L118-L123) はuserId/domainNameだけで絞り、SUCCESS行も再更新する。
したがって「重複配信時のcompletedAt不変」を既存契約として要求しない。IDの0/負値拒否も現イベント/APIへ無断追加しない。

候補の依存方向はKB→gdpr.apiであり、gdprは自分のSPIだけを静的参照する。
公開窓口追加だけでKBを含む循環0と宣言しない。common→gdpr等も含む全体verify、橋渡し以外の辺の確認、必須化リストへの掲載が別途要る。

## 3. 削除の途中失敗とchartの留保

KBは既に削除と完了報告が別TXである。
削除rollbackならafterCommit報告なし、削除commit後の完了更新失敗ならデータ0/PENDINGとなる。
回復はSYSTEM_ADMINの既存手動retryで削除0件を再実行し、完了を保存する経路を保つ。
[日次audit](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/gdpr/batch/GdprPurgeAuditBatchService.java#L70-L103) のPENDING検出を、全domain purgeの自動再実行と誤記しない。

chartはKB最小束の対象外。将来chartを返済する際の独立論点は次のとおり。

- [chart Listener](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/chart/event/ChartPurgeEventListener.java#L51-L105) は匿名化とgdpr SUCCESSを現在同じTXに含む。KBと同じ別TX方式へ変えると匿名化済/PENDINGの新しい欠落窓を導入する。
- [legacy retry](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/gdpr/service/GdprPurgeRetryService.java#L71-L139) は外側gdpr TXからchart REQUIREDを呼ぶ。設定28domainの無TX調停と一括共通化しない。
- gdpr→chartの逆辺は [preview](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/gdpr/controller/GdprController.java#L177-L185)、[collector](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/gdpr/service/PersonalDataCollector.java#L195-L245)、retryに残る。chart→gdpr.apiもある状態で単にgdpr→chart.apiへ替えると循環は消えない。
- 候補SPIはgdpr.api所有の `DeletionPreviewContributor { String category(); long countForUser(Long userId); }` と `PersonalDataContributor { String category(); String collectJson(Long userId); }`。chartが実装し、Entityを出さず従来JSONを所有TX内で完成させる。preview本人全件とcollector shared=trueの条件差・順序・日付/null・charts.json名を保つ。
- purge SPIの正式形、Bの欠落回復/担当/手動期限を許容するかCを採るかは別途判断する。chart backfillがcompletionまで直す証拠はない。ログだけのChartAnonymizationServiceを返済済み実体と扱わない。

[PersonalDataCoverageValidator](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/gdpr/PersonalDataCoverageValidator.java#L31-L50) も注釈型を直接scan/getAnnotationする。
将来の `gdpr.api.PersonalData` 候補はTYPE/RUNTIME/categoryを維持し、全Entity importとscanner/試験を同じ合意済み変更束で移す。注釈削除やscan漏れで返済しない。KB最小束には不要。
collectorの現在のcharts失敗→[]と他カテゴリ失敗→FAILEDの違いも無断統一しない。

ここでCのRegistryはSpring Modulith Event Publication Registryであり、adminのBatchEndpointRegistryとは別物。
本固定SHAのGDPR本番にPurge Registry/SPI、ReliableEvent、ModuleEventListenerが導入済みとは扱わない。
今回は既存Bイベントの公開候補だけとし、C採用時はL1-Mの正式な時刻/serialize・限定永続化・再配信/隔離・認可再検証契約を待つ。
purge後は利用者が不在なので、再検証を「現存ユーザー必須」にして削除を不能にしない。確定したpurge対象/監査証跡で判定する契約が必要となる。

## 4. admin：注釈だけを動かさない

公開候補は `com.mannschaft.app.admin.api.batch.BatchEndpoint`。
[現注釈](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/admin/batch/BatchEndpoint.java#L40-L60) のRUNTIME/METHOD、必須 `String name()`、`String description() default ""`だけを維持する。
gate/retry/tx/認可属性や新ファサードを追加せず、Registry/Descriptor/監査Entity/Service/Event/HTTP DTOは内部に残す。
[境界計画§3.4](../architecture/modular_monolith_boundary_debt_repayment.md#34-公開窓口の規約) の公開型列挙に注釈は明記されないため、付録Aの「注釈を所有側公開境界へ、新旧をaspectが認識」を本マーカーにも適用する旨を合意へ含める。
api.batchのNamedInterface/package-info配置はL1-M/G規約に揃え、サブパッケージが実際に公開されるverifyを予定する。

| 検出器/保護 | 現参照と必要な同時対応 |
|---|---|
| Registry | [旧classのmerged annotation走査](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/admin/batch/BatchEndpointRegistry.java#L79-L108) を新旧対応。Method単位に1登録。新旧別Method/Beanの同名、空名はfail-fast維持。同Method二重付与も起動時拒否する候補。 |
| BatchExecutionAspect | [旧FQCN pointcutと旧class resolver](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/admin/batch/BatchExecutionAspect.java#L46-L109) を両方対応。新旧ORの1 adviceで本体/監査/Event各1回。片方だけ替えると監査欠落/解決失敗する。 |
| gate Aspect | [jobNameOfの旧class](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/common/backgroundgate/BackgroundFeaturePolicyAspect.java#L146-L155) も対応し、SKIPPED/RESUMED名がFQCN#methodへ分裂しないこと。Order(5)で監査より外側のまま。 |
| Scheduled番人 | [ScheduledBatchGuardTest:631](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/test/java/com/mannschaft/app/common/architecture/ScheduledBatchGuardTest.java#L627-L644) とConditionTest/fixtureを指定ownerが新旧対応。Batchなし/無根拠免除を通さない。 |
| 禁止経路番人 | [ScheduleKeepNoReminderArchTest:189](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/test/java/com/mannschaft/app/common/architecture/ScheduleKeepNoReminderArchTest.java#L184-L191) も新型を検出し、手動リマインド禁止の穴を開けない。 |
| その他の検査 | BatchMarkerAnnotationGuardTestの免除理由/JavadocとBackgroundEntryPolicyDeclarationGuardTestのScheduled/Policy検出は維持。Blog/Reservation/NotificationOutbox/FeeReconciliation/ReturnStayPlan/TeamRegionBackfillのgetAnnotation試験は各caller ownerの移行時に更新する。 |

[手動起動](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/admin/controller/SystemAdminBatchController.java#L156-L238) はgate拒否409→lock拒否409→sync 200/500またはasync 202を維持する。
[SecurityConfig](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/config/SecurityConfig.java#L549) のSYSTEM_ADMIN制限は変えない。注釈の公開化に認可効果はない。
KBの [runCleanup](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/knowledgebase/service/KbImageCleanupBatchService.java#L39-L45) は `knowledgebase-orphan-image-cleanup-daily` / ALWAYS / 02:00 JST / kb_image_cleanup / PT15Mを維持する。
chartの [backfill](https://github.com/kenta-0420/mannschaft/blob/d4cc6d7fcf8d618de374fc89c5baacfbeb825cb1/backend/src/main/java/com/mannschaft/app/chart/batch/ChartPurgeBackfillBatchService.java#L67-L79) は `chart-purge-backfill-daily` / ALWAYS / 03:00 JST / chartPurgeBackfillBatch / PT30M・PT1Mを維持。共にvoid/no-arg/Transactionalのまま、caller ownerはimport/付与型だけを置換する。

旧型は残ったcallerのため認識を保ち、全caller/test/reflection/実行FQCN参照を移してから撤去を調整する。
Javadoc/凍結テキスト/重み表にも旧文字列があるため、検索結果を一括置換の対象にしない。

## 5. 所有と将来の統合順

| 範囲 | 編集owner/先行調整 |
|---|---|
| gdpr公開イベント/完了窓口/設定SPI/KB分岐 | GDPR ownerが支援契約を所有。全購読型変更は各ownerと統合束を先に合意。KB ownerは自Listener/SPI実装だけ。 |
| admin公開注釈/Registry/監査 | admin owner。KB ownerは支援着地後にKB1箇所、chart ownerは波割当後にchart1箇所。 |
| Scheduled/ScheduleKeep番人・Modulith必須化/ストア | 指定共有資産owner。repayment-cが勝手に既存番人/定数/重み/ストアを変更しない。 |
| common.backgroundgate 5型 | 付録A:1244–1248のL1-R所有。注釈/enum→admin.api.featuregate、Aspect→admin内部、Evaluator/Recorderも所有側へ。jobNameOf対応の所在/順序をL1-Rと調整。 |
| S3/Storage移行 | 付録A:1358の純粋削除＋admin内部adapterはL1-R＋L2-admin調整、:1380–1383のStorageMigration群はL1-R。Batch契約に便乗して移設しない。 |

1. 親IssueでL1-F/G等入口、支援契約と波の統合範囲、公開候補/編集ownerを確定し、最新api/移設先を再確認する。現在はこの前段まで。
2. 開始許可後に現挙動characterizationを保存し、新型未対応/KB逆辺の予定試練をred実測する。既存挙動の初回greenをredと呼ばない。
3. GDPRは完了窓口/SPIとKB逆辺を同じ統合単位で整合させる。イベント移設は全購読ownerと合意した束で1回発行/1回購読を保つ。
4. adminは新旧対応のRegistry/Aspect/gateログ名/番人を先行して整合させる。L1-R前なら旧common側、後なら移設先だけを編集し、新旧Aspectを二重Bean登録しない。
5. 支援が揃ってからKB ownerが自分の呼出型を置換する。chart全体のSPI/原子性/PersonalData移行と他callerは別の合意済み範囲で進める。
6. 将来の全archUnitTest/verify/HTTP比較とAC証拠を統合し、説明できる減少行だけを指定ownerが更新する。KBロット/相手ロットの完了はそれぞれの出口で判定する。

## 6. AC・予定試練・実装対応（すべて未検証）

試験名は予定であり、コードは作成していない。実装後はAC→試験名/結果XML→実装差分をPRに対応付ける。

| AC | 検証可能な条件 / red・green候補 | 実装/owner |
|---|---|---|
| C-G1 境界/正常 | KB→gdpr非api参照0、gdpr→KB具体DI0。公開イベント/API/SPIだけの署名試験をred→green。最後にKB含む循環0・非api0を全体verifyで実測。公開型だけの追加を完了にしない。 | GDPR窓口/SPI/KB枝、KB Listener。 |
| C-G2 空/0/null/重複 | favorite 0/1/多件、別owner保持、重複でも削除増殖なし。userId null拒否、0/負値の既存挙動、未知domain/行なし/既SUCCESS skip/完了更新0件を固定。completedAt不変は要求しない。 | 既存AccountPurgedEventTest / PersonalSettingsAccountPurgeITを照合。不足はKB対象に追加。 |
| C-G3 途中失敗 | KB削除SQL/commit失敗→rollback/PENDING/完了未呼出し。削除commit後の完了失敗→0件/PENDING→実手動retryでSUCCESS。owner失敗はfalse/PENDING/試行数+1、完了save失敗は伝播。 | KB対象のcommit障害ITとGdprSettingsPurgeRetryServiceTest。テスト全体@TransactionalでAFTER_COMMITを隠さない。 |
| C-G4 境界/性能 | SPI二重/必須KB欠落/既存switchとの二重所有を起動時拒否。Map起動一回、所有DELETE一括、完了報告/Bean探索のN+1なし。queue拒否→PENDINGと既存手動回復も照合。 | GDPR内部MapとKB proxy。PersonalSettingsAccountPurgeITの既存定義を裏取り。 |
| C-A1 登録/空/境界 | 新旧各1登録、description空/lockなし/履歴nullを維持。空/blank、同名別Method、新旧二重付与はfail-fast、引数あり/未知名拒否。scan-disabledは空でlazy生成0。 | BatchEndpointRegistryTestへ新旧fixture。未対応新型をredにする。 |
| C-A2 監査/途中失敗 | 実proxy→Registry起動で本体/開始/完了または失敗/Event各1、片側pointcut変更を赤にする。start失敗は本体0、fail/Event補助失敗でも元業務例外、complete/Event補助失敗でも戻り値維持。 | 予定BatchEndpointCompatibilityITと既存AspectTest。直呼びmockだけでpointcutを証明しない。 |
| C-A3 0/null/境界 | void/null/非数値0、Integer0、Long int上下限ちょうど/各±1、例外messageなし/2000・2001文字を保持。lock/cron/zone値とShedLock付きprimitive拒否は不変。 | Aspect境界試験＋caller宣言/番人。既存long=123L試験を上下限実証と扱わない。 |
| C-A4 gate/保護 | 新旧同nameのSKIPPED初回/連続skip、復帰RESUMED、skip時本体/監査/Event0。ALWAYSはflag参照0。manual無効409はexecutor0。Scheduled旧新は通し欠落は落とす、ScheduleKeep禁止経路の新型検体も落とす。 | L1-R/admin/番人owner。jobNameOf/Order/検出器を個別に壊した変異を赤にする。 |
| C-X1 認可/排他 | GDPR retryとbatch一覧/status/triggerは実filterで未認証401、一般/TEAM・ORG管理者/他tenant管理者403、SYSTEM_ADMIN正常。拒否でowner/Registry/executor0。batch 404/409/500維持、実ShedLock同時呼出で本体1回。 | SecurityFilterChain有効の契約IT。addFilters=falseの既存Controller試験だけで認可greenにしない。 |
| C-X2 性能/公開契約 | 同fixtureのSQL/登録集合を比較し追加全Bean走査・履歴I/O・二重adviceなし。前後同手順生成OpenAPI差分0、既存JSON/順序/null/同期非同期応答一致。旧callerのRegistry経由ITも維持。 | admin/各owner、TeamNotificationOutboxRelayIT / VillageEventRefluxIntegrationITを照合。 |

正常はC-G1/C-A2、攻め口5類型は空/0/null=C-G2/C-A1/C-A3、境界=C-G4/C-A1/C-A3、途中失敗=C-G3/C-A2、認可=C-X1、性能=C-G4/C-X2で対応する。
KBの既存業務テストや第1波計画ACを置き換える表ではなく、支援契約の追加分である。

chartを扱う将来束では、同TX現状のcharacterization→分割時の欠落回復、preview/collector条件差、PersonalData scanner陽性/陰性と全逆辺を別ACへ具体化する。
C採用時は例外未完了・再配信・冪等・発行TXrollback・権限/状態再検証・purge後復活なし・時刻serialize往復、限定永続化の負IT/再投入/隔離もL1-Mの証拠が必要となる。

## 7. 完了扱いにしないもの

本書は計画Markdownの保全であり、L2 working/done、API合意/提供、L1-F/G/M完了、番人green、実機免除の証拠ではない。
既存試験のソース定義は読んだが今回のgreenは確認していない。skip/環境未実行/XML欠落は合格にしない。
最終差分が既存B挙動を保つ内部整理か、B/C・原子性・信頼性の変更かを改めて判定する。
境界計画§8.4のOpenAPI差分0・既存IT無変更green等を実測するまで実機免除を確定しない。
免除条件を満たさない場合は権限あり/なし/他tenant・URL直打ちの実機E2E＋アリシゼーションを実施し、遅延/重複/欠落の観測も追加する。

今回の追跡成果は本書1ファイルのみ。台帳・番人・ストア・業務コード・API実体は変更しない。
