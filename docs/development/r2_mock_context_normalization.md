# R2 mock の field 名統一（CMP-261004-2047 Phase 1）

同じ `R2StorageService` を `@MockitoBean` で置き換える IT の field 名を
`r2StorageService` に統一する。Spring Framework 6.2.17 は name 未指定の
by-type override でも field 名を context cache key の比較に使うため、
`storageService` と `r2StorageService` の違いだけで別 context が保持される。
mock の型・外部境界、annotation、stub 値、業務 assertion は変えない。

対象は filters 無効の 7 IT（budget/chat/circulation/filesharing/match/skill 2 本）と、
filters 有効の receipt 3 IT。filters の異なる構成は共有しない。
所有権移譲の IT、共通 MySQL 基底、heap/cache/fork/shard 設定は変更しない。

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
全体の context 生成・close 数は未計測であり、この 2 key 削減だけで OOM が解消したとは主張しない。
全量 2 回連続 green の達成と出荷判断は別途 CI の証拠で確認する。

製品の UI/API を変更しない test-only のため、今回変更の実機 E2E・探索試験は対象外。
別の製品機能の実機結果を本件の完了証拠へ転用しない。
