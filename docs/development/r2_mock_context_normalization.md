# R2 mock の field 名統一（CMP-261004-2047 Phase 1）

同じ `R2StorageService` を `@MockitoBean` で置き換える IT の field 名を
`r2StorageService` に統一する。Spring Framework 6.2.17 は name 未指定の
by-type override でも field 名を context cache key の比較に使うため、
`storageService` と `r2StorageService` の違いだけで別 context が保持される。
mock の型・外部境界、annotation、stub 値、業務 assertion は変えない。

対象は filters 無効の 7 IT（budget/chat/circulation/filesharing/match/skill 2 本）と、
filters 有効の receipt 3 IT。filters の異なる構成は共有しない。
所有権移譲の IT、共通 MySQL 基底、heap/fork/shard 設定は変更しない。
Phase 1 当初は cache/timeout も維持したが、全量失敗後に nightly の実行設定を改訂する（後述）。

## 試験と受け入れ条件

| 条件 | 試験・確認 |
| --- | --- |
| 同じ構成の共有、代表 4 IT の 4→2 key | `R2MockContextConfigurationTest` が公開 Spring bootstrap API の実 `MergedContextConfiguration` を比較する。rename 前は共有 2 case と key 数 1 case が assertion failure（実測 4 key）、rename 後は同じ期待を検証する |
| 必要な分離 | 同 guard が filters、追加 mock、spy、qualifier、profile、name 未指定の field 別名を非等価として検証する |
| metadata 構築の非起動性 | 同 guard の static 初期化検体で確認する。metadata 構築で DB/context を起動しない |
| 実 context 共有 | `R2MockContextSharingIT` の 1 case が既存 4 IT の metadata で load し、各 pair の identity 一致、R2 bean 唯一・同じ Mockito mock、filters 間の identity 非一致を検査する |
| 既存挙動保存 | 対象 10 IT と比較正規側 2 IT の業務 case を実行する。既存 case は削除せず、新規 guard 10 case と共有 probe 1 case を追加する |
| 全量ゲート | migration 込み 6 shard＋arch 系を 2 回連続実走 green、OOM 0、既存 suite/case 欠落・重複 0、skip 理由を確認する。ローカル対象試験だけでは完了としない |

metadata 専用検体は 7 class と空の構成 class 1 本で、実 context は生成しない。
共有 probe 自身にも新しい `@SpringBootTest` 構成は定義しない。
Docker 不在時はプロジェクト規約に従い probe が skip されるため、実共有の確認には
**1 case の実実行・skip 0** を要求する。起動・DI・fixture 失敗を red 証拠や合格に数えない。

ローカルの絞り込み検証は `gradle-turnstile.sh` 経由で行い、凍結ストアを誤縮小しないよう
`-x archUnitTest -x archUnitFreezeStoreIntegrityTest` を指定する。
これは対象検証だけの除外であり、CI 全量の Arch/guard は従来どおり必須とする。
凍結ストア 8 ファイルの前後 SHA も比較する。

nightly full は `test --rerun` / `archTest --rerun` で実走を保証する。
Gradle 8.14.4 の task 単位オプションであり、依存 compile の build cache は維持する。
`--rerun-tasks` は使用しない。

## 計測範囲と残るゲート

2026-10-18、基準 `075492708752feae12031494c0b6b031a4b5967c` からの隔離環境で、
対象 10 IT＋比較正規側 2 IT＋追加 2 suite を実行し、353 case（既存 342＋追加 11）、
失敗 0・skip 0 を確認した。XML は nested を含む 73 suite、壁時計 802.5 秒、
テスト JVM は 2 本・各 4096MB、凍結ストア 8 ファイルの前後 SHA は一致した。
追加 metadata 検体の Spring Boot 基底への補強後も、同じ 10 case を別途再実行し、
失敗 0・skip 0（83.4 秒）、凍結ストア 8 ファイルの前後 SHA 一致を確認した。
この補強は初期化検体の継承元だけの変更で、red を取得した共有 2 case と key 数 case の期待は不変。

修正前は metadata の 4 key を計測し、4 つの実 context は起動していない。
修正後の代表 2 context 共有は上記 probe で検証する。
全体の context 生成・close 数と retained heap 内訳は未計測であり、
この 2 key 削減だけで OOM が解消したとは主張しない。

## 全量失敗と nightly 実行設定の改訂

Phase 1 の固定 head `5aad90ef1531e3b8649343f5872fec3e807d33a5` に対する
nightly full run `39126089014` は失敗した。shard 0 の安全ログ集計では実 OOM marker 3、
test 5074 case 中成功 5053・失敗 20・skip 1、migration 除外 false を確認した。
安全 XML 集計では20失敗はすべて `OwnershipTransferOfferScopeContractIT` の
5 nested suite に属し、例外型はいずれも `IllegalStateException` だった。
失敗順・最初の cause は未確認であり、OOM との因果関係は断定しない。
shard 4 は最大実行時間超過の annotation と job 実時間 90分18秒を確認した。
shard 3/4 の test step は cancelled、shard 0 は test step failure 後に job cancelled であり、
job と test step の結果を分けて確認し、cancelled を成功と扱わない。上記の局所 353 case green と
代表 metadata の 4→2 key は維持するが、全量成功の証拠にはならない。

通常 CI（`backend-ci.yml`）は context cache 上限5、migration 込みでは
job 120分・test step 105分である。一方、失敗時の nightly は cache 上書きなし
（Gradle 既定10）・job 90分だった。保持 context の上限と全量実行可能時間の差を解消するため、
nightly の test に `-Pspring.test.context.cache.maxSize=5` を追加し、
full-test job を120分、test step を105分へ揃える。
job と step の差15分は compile・結果アップロード等の余裕とする。
setup/compile が15分を超える場合、test 105分と後処理の余裕は保証しない。
これは Phase 1 の cache/timeout 不変更方針の明示的な改訂であり、
field 名統一だけで OOM が解消するという仮説は棄却する。
cache5 で必ず解消すると断定せず、新固定 head の全量実走で検証する。

heap 4g、forkEvery 180、parallel 2、6 shard と選別・重み表、migration、
Java/test/Gradle のソースはこの改訂で変更しない。
専用 archTest の単独 JVM・3g・30分、compile cache の read-only、
test/archTest の task 単位 `--rerun` も維持する。
過去の cache5 診断 run `38682333037` は migration 込み shard 0 の test が
約97分46秒で成功したが、workflow head `5c017` と製品 checkout `1c6411fc` は異なり、
今回のソースとも異なる。1 shard の診断を6 shard全量成功や同条件の性能比較の根拠にしない。
変更前と case 集合が完全一致する全量成功 baseline もないため、
ソース保持・選別不変更と今回の全6 shard 実走の証拠は区別する。

## 修復後の全量ゲート結果

固定 source head `522bfb7fe6ea4b665c384b86411120cc5f755bc8` の
[run 39135193568](https://github.com/kenta-0420/mannschaft/actions/runs/39135193568) と
[run 39170400055](https://github.com/kenta-0420/mannschaft/actions/runs/39170400055) は、
migration 込み6 shard＋専用 archTest＋shard別 archUnit engine/後段 guard を
同一 head で2回連続実走して success。各runは7 artifact・7301 suite・31658 case、
failure/error 0、既存skip 17、追加11 caseのskip 0。migration 80 class/195 case、
専用Arch 299 case、shard内engine 37 case、後段guard 12 caseを照合した。
安全ログは全7対象jobでcompleteRead、実OOM/未分類marker 0、migration除外false、
task実走、outer class複数artifact配置flag 0。全taskのcase識別と状態の多重集合比較では
欠落・追加・状態差分0だった。既存skipを成功へ算入しない。表示名が衝突する233群の
Java method完全同定は未検証。安全集約原本はローカルの
`.claude/evidence/proxy-settings-20261018/ci-context-normalization/root-full-ci-acceptance-522bfb7f.json`。

通常mergeされた [PR #3986](https://github.com/kenta-0420/mannschaft/pull/3986) は
PR head `a920be6259e8fd7fdba2f0436cfefc5f160b4dab`、main merge commit
`33caf032d4ea8d33f28225326ac4610f90004646`。固定source headとは別headだが、
両者のbackend Git tree `92badf96720ea2c15d188d4ff46ff25c28d9318d` と
nightly workflow blob `c24139e65721091bf79b79dcb6d19d919c563fd6` は一致する。
PR head上の[必須Backend CI run 39179907298](https://github.com/kenta-0420/mannschaft/actions/runs/39179907298) と
[Task-List PR Gate run 39186764926](https://github.com/kenta-0420/mannschaft/actions/runs/39186764926) もsuccess。
PR head自体で上記2回の全量実走を行ったとは記録しない。

旧head `5aad90ef1531e3b8649343f5872fec3e807d33a5` の全量失敗と
ローカル353 case greenは別条件の履歴であり、同条件性能比較に用いない。
全runnerのcontext生成・close総数とretained heap内訳、変更前の同一case集合の
全量成功baselineは未計測。field名統一だけでOOMを解消したという因果は断定しない。
CMP-261004-2047の約135 ITを含む全体改善は引き続き実装中とする。

## main CI の実行設定整合と未完了ゲート

main merge commit `33caf032d4ea8d33f28225326ac4610f90004646` の
[Backend CI run 39186841443](https://github.com/kenta-0420/mannschaft/actions/runs/39186841443) は
completed/cancelled。専用 Arch と shard 2/3 は success だったが、shard 0/1/4/5 は
75分台で cancelled となり、4 job に最大実行時間の annotation があった。
安全ログは7 jobすべて completeRead、6 shard の migration 除外は false。
shard 0 に実 OOM marker が2行あるが、独立した2件とは数えず、発生 task/class も未特定。
他の6 jobの実 OOM markerは0。取消された4 shard の通常 test 完走 case 数は未取得であり、
task の EXECUTED 行だけを全量成功の証拠にしない。この run は main 上の全量ゲートを満たさない。
安全集約はローカルの
`.claude/evidence/proxy-settings-20261018/ci-context-normalization/main-39186841443-safe-log-projections.json` と
`main-39186841443-timeout-annotations.json` を参照する。

上記 main の backend tree `92badf96720ea2c15d188d4ff46ff25c28d9318d` と nightly workflow blob
`c24139e65721091bf79b79dcb6d19d919c563fd6` は、2回連続全量成功した固定 head
`522bfb7fe6ea4b665c384b86411120cc5f755bc8` と一致する。ただし main の test job は
75分・test step上限なし・context cache既定10で、全量成功時の nightly は
120分・test step105分・cache5だった。別条件の2成功を main の成功へ読み替えない。

main の test job を通常 PR と同じ条件付き job 120/100分・test step 105/85分・
`-Pspring.test.context.cache.maxSize=5` に揃える。migration 判定、6 shard、
テスト選別、heap/fork、Arch、deploy の `needs` と AWS guard は維持する。
これは成功実績のある有界 profile への整合であり、時間延長だけで成功を主張しない。
cache10 が遅延または OOM の根因だったか、cache5 で解消するかも未確定。
新しい main run で6 shardと専用 Arch の成功、各 profile と XML の完全性、実 OOM 0を
確認するまで、main 全量ゲートは未了とする。

製品の UI/API を変更しない test-only のため、今回変更の実機 E2E・探索試験は対象外。
別の製品機能の実機結果を本件の完了証拠へ転用しない。
