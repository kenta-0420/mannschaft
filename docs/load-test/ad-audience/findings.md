# 広告オーディエンス解決（AdAudienceResolver）縮小版負荷試験 findings

- **背景**: `AdAudienceResolver.resolve()` は配信対象 user_id をすべてメモリ上の `Set<Long>` に載せ、
  Java 側で積集合・差集合を取る。ユーザー数が50万〜100万規模になったときメモリが持つのか判断したい。
- **射程**: **マスターの裁可により「50万件の本番同等試験」ではなく「5万件規模で実測し、100万件まで
  外挿する」縮小版**で実施した。1件あたりのバイト数・件数に対する線形性を見るのが目的であり、
  絶対値の本番実証ではない。
- **測定 IT**: `backend/src/test/java/com/mannschaft/app/advertising/campaign/perf/AdAudienceResolverScaledMeasurementIT.java`
- **seed ヘルパー**: `backend/src/test/java/com/mannschaft/app/support/perf/AdAudienceSeeder.java`
  （`Fanout500kSeeder` を金型にした JDBC バッチ INSERT。広告セグメント評価器が参照するハッシュ列を埋める点が異なる）

---

## 1. 各評価器の参照列（確定結果）

コードを実際に読んで確認した。

| セグメント種別 | 評価器 | 参照列 | 備考 |
|---|---|---|---|
| REGION_PREFECTURE | `PrefectureSegmentEvaluator` | `users.prefecture_code_hash` | HMAC-SHA256 ブラインドインデックス（V68.002） |
| GENDER | `GenderSegmentEvaluator` | `users.gender_hash` | 同上 |
| REGION_CITY | `CitySegmentEvaluator` | `users.city_code_hash` | 同上 |
| INTEREST_TAG | `InterestTagSegmentEvaluator` | `user_interest_tags.tag_hash`（別テーブル） | HMAC-SHA256（V68.003） |
| AGE_RANGE | `AgeRangeSegmentEvaluator` | `users.birth_year`（平文 SMALLINT UNSIGNED NULL） | 生年比較に変換して BETWEEN 検索（V68.004）。`birth_date` 自体は暗号化されているため専用の平文列を持つ |
| LOCALE | `LocaleSegmentEvaluator` | `users.locale`（平文・暗号化なし） | NOT NULL 列、JPQL の `IN` 検索 |
| ORG_TYPE | `OrgTypeSegmentEvaluator` | `teams.template` を `user_roles` 経由で JOIN | users 側に専用列はない。クロスドメイン参照だが SELECT のみ（FK なし） |
| DEVICE | `DeviceSegmentEvaluator` | `push_subscriptions.user_agent` | UA パースが必要なため本シーダーの対象外（COUNT 不可） |

本シーダーは実測に使う3セグメント（`REGION_PREFECTURE` / `GENDER` / `INTEREST_TAG`）に必要な列のみを埋めた。
AGE_RANGE / LOCALE / REGION_CITY / ORG_TYPE / DEVICE は列の存在・参照経路を確認したのみで、
本負荷試験では実測していない（第7節参照）。

---

## 2. 実行方法

```bash
cd backend
# 事前に WSL2 Docker が生きていること（Testcontainers 用）
export DOCKER_HOST=tcp://127.0.0.1:2375
export TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1
./gradlew perfTest -Pmax.parallel.forks=1 --tests "*AdAudienceResolverScaledMeasurementIT*"
```

- `DOCKER_HOST` は **ループバック限定（`127.0.0.1:2375`）** で運用すること。これが正規手順である。
  Docker デーモンのソケットを `0.0.0.0` へバインドして外部公開する手順は、無認証で到達可能な穴を
  開けることになり**絶対に行ってはならない**（本出陣中に一度誤って行い、殿の指摘を受けて是正した。
  以後の実行環境構築にこの誤りを持ち込まないこと）。
- `perfTest` は `@Tag("perf")` のみを実行する専用タスクであり、通常の `test`/CI からは除外される
  （`build.gradle.kts` の `perfTest` タスク定義で `includeTags("perf")` を適用）。
- **SKIP 偽緑への注意**: 基底 `AbstractMySqlIntegrationTest` の `@EnabledIf(isDockerAvailable)` は
  Docker 不通で静かに SKIP する。本試験は JUnit XML の `tests="1" skipped="0" failures="0"` と、
  Docker 経由で MySQL Testcontainers が実際に起動したログ（`tc.mysql:8.0 -- Container ... started`）を
  確認し、実 RUN であることを裏取りした。

---

## 3. 健全性チェック（実測）

`AdAudienceSeeder` の分布設計（都道府県5種に `i % 5` で均等分散、性別3種に `i % 3` で均等分散、
興味タグは `i % 4 == 0` の4人に1人へ付与）に対し、`AdAudienceResolver` が計算した `matchedCount` /
`resultCount` が期待値と**完全一致**することを hard assert で確認した（3点とも成立、テストは green）。

| memberCount | 単一INCLUDE(都道府県1件) matchedCount | 期待値(1/5) | 判定 |
|---|---|---|---|
| 10,000 | 2,000 | 2,000 | 一致 |
| 30,000 | 6,000 | 6,000 | 一致 |
| 50,000 | 10,000 | 10,000 | 一致 |

複合ケース（都道府県2件INCLUDE・性別1件INCLUDE・興味タグ1件EXCLUDE）の `resultCount` も
memberCount のちょうど10%で3点とも一致した（都道府県2/5 交 性別1/3 交 興味タグ非該当3/4 の
独立分布同士の掛け合わせと整合）。**0件のまま測定を続けた事実は無い**。

---

## 4. 3点測定の実測値（時間・件数）

| memberCount | seed_ms | single_matched_count | single_resolve_ms | complex_result_count | complex_resolve_ms |
|---|---|---|---|---|---|
| 10,000 | 3,035 | 2,000 | 48 | 1,000 | 544 |
| 30,000 | 5,191 | 6,000 | 82 | 3,000 | 476 |
| 50,000 | 7,732 | 10,000 | 64 | 5,000 | 586 |

`complex_resolve_ms`（都道府県2件OR＋性別AND＋興味タグEXCLUDE、積集合・差集合が実際に走る経路）は
1万〜5万件のレンジで概ね500ms前後であり、件数に対して急激な悪化は見られない
（このレンジでは DB 往復コスト・Spring/Hibernate のオーバーヘッドが支配的で、
`Set` 演算そのものの CPU コストはまだ支配的要因になっていないとみられる）。

---

## 5. ヒープ測定 - 失敗（正直に記録する）

### 5.1 何を試したか

`resolve()` 呼び出し前後で `System.gc()` を挟み `Runtime.totalMemory() - Runtime.freeMemory()` を
複数回（5回）測定して中央値を取る方式を試した。

### 5.2 結果と失敗の理由

```
member_count=10000  heap_delta_bytes=-993320  bytes_per_user=-993
member_count=30000  heap_delta_bytes=+1000624 bytes_per_user=333
member_count=50000  heap_delta_bytes=-53608   bytes_per_user=-10
```

**`heap_delta_bytes` が負の値を取っており、これは測定として成立していない**（負のメモリは存在しない）。
原因は明確である: Spring Boot テストコンテキストのヒープ全体は実測で約492MBあるのに対し、
測定対象の `Set<Long>` は最大でも1万件（数百KB規模）しかなく、**測りたい量がGCの揺れ
（実測で見えたレンジは概ね±1MB）より小さい**。この規模・この測定方法では原理的に測れない。

この `bytes_per_user` の数値は**捏造してはならないためそのまま記録するが、実測値として採用しない**
（第6節の外挿計算には使わない）。

### 5.3 代替案の検討と採用

以下2案を検討し、**(a) 要素数からの計算（実測ではなく計算）** を採用した。

- **(a) 要素数からの計算（採用）**: `resolve()` が保持する `Set` の要素数（第3〜4節で実測済み）から、
  `HashSet<Long>` 1要素あたりの既知のJVMオブジェクトレイアウトに基づく理論値でメモリ量を計算する。
  GCの揺れに影響されず、縮小版の趣旨（規模を上げない）とも整合する。
- **(b) 規模を上げてGCノイズより十分大きくする**: 原理的には有効だが、縮小版という裁可の趣旨
  （50万件本番同等試験を避ける）に反するため不採用。

### 5.4 (a) の計算根拠

64bit JVM・圧縮オブジェクトポインタ（Compressed Oops、ヒープ32GB未満で既定有効）を前提に、
`HashSet<Long>`（内部的に `HashMap<Long,Object>` でバックされる）の1エントリあたりのコストを
以下のように積算した（JOL 等での一般的な計測値とも整合する範囲）。

| 要素 | サイズ | 備考 |
|---|---|---|
| `HashMap.Node` オブジェクト | 32 bytes | オブジェクトヘッダ16B + hash(int)4B + key参照4B + value参照4B + next参照4B（8B境界に整列済み） |
| ボックス化された `Long` オブジェクト | 24 bytes | オブジェクトヘッダ16B + long値8B。ユーザーIDは -128から127 のキャッシュ範囲外のため毎回新規オブジェクトが生成される |
| バッキング配列スロット（償却） | 約5.3 bytes | 配列参照4B を既定ロードファクタ0.75で割った値 |
| **合計（採用値）** | **約64 bytes/エントリ** | 上記合計 約61B を丸めた保守的な値 |

**この数値は測定ではなく計算である**ことを明記する。実際のJVMバージョン・GC実装・
ポインタ圧縮の有効/無効によって変動しうる（一般に文献上は48から80 bytes/エントリ程度のレンジで
語られることが多く、64 bytes/エントリはその中庸）。

---

## 6. 50万件・100万件への外挿（計算であり実測ではない）

### 6.1 最終結果件数（resultCount）ベース

実測比率（複合ケース: memberCount の 10%）をそのまま延伸すると:

| 規模 | resultCount（10%） | 推定メモリ（64B/entry） |
|---|---|---|
| 500,000 | 50,000 | 約 3.2 MB |
| 1,000,000 | 100,000 | 約 6.4 MB |

殿の見立て（単一セグメントの20%比率・約10から20MB）とはベースにした比率が異なる
（殿は `single_matched_count` の20%比率、本項は複合ケースの最終`resultCount`である10%比率）。
どちらで見ても **メガバイトから十メガバイト台** であり、桁としては一致する。

### 6.2 peak時（resolve() 内で同時に保持されるSetの合計）ベース - 見落としてはならない観点

`AdAudienceResolver.resolve()` の実装（`AdAudienceResolver.java` 122から174行目）を読むと、
**各セグメントの評価結果 `Set` は、最終的な積集合を取る前にすべて同時にメモリ上に保持される**
（`includeByType` マップに type ごとの和集合 Set・`excludeUnion` に EXCLUDE の和集合 Set が
それぞれ独立に保持され、全セグメント評価が終わってから初めて `retainAll` / `removeAll` で
絞り込まれる）。

本試験の複合ケースでは、都道府県2件OR(memberCountの40%) ＋ 性別1件(33.3%) ＋
興味タグEXCLUDE(25%) の3つの `Set` が**同時に**メモリ上に存在する瞬間があり、
その合計は memberCount の約98%相当のエントリ数になる（最終 `resultCount` の10%よりずっと大きい）。

| 規模 | peak時の概算エントリ数（3セット合計・本試験の分布比率） | 推定メモリ（64B/entry） |
|---|---|---|
| 500,000 | 約 491,000 | 約 31.4 MB |
| 1,000,000 | 約 983,000 | 約 62.9 MB |

これも**十メガバイト台**であり、殿の見立て・第6.1節の最終件数ベースと桁としては変わらない。
ただし本試験の分布（セグメントの的中率が40%・33%・25%）は合成データの設計値であり、
**実際の広告主がどれだけ広いターゲティング条件を設定するか**（例: 5セグメントすべてを
50%超でINCLUDEするような極端に広い設定）次第でこの係数は変わりうる。理論上の最悪ケース
（全セグメントが人口の100%に一致する状態でN個のセグメントを設定）では
`N かける memberCount かける 64B` に達するため、セグメント数が多い・かつ広いキャンペーンが
将来的に増える場合は再検証が必要。

### 6.3 結論（マスターの判断材料）

- 本試験で観測できた範囲（1万から5万件・3セグメント構成）を素直に延伸する限り、
  50万から100万件でも `resolve()` が保持する `Set` 群の合計は**数十メガバイト規模**に収まり、
  一般的なJVMヒープ（数百MBから数GB）を圧迫するとは考えにくい。
  **殿の見立て（急いで第二段（候補ID絞り込み）に着手する必要はなさそう）に同意する。**
- ただしこれは「本試験で使った程度の広さのセグメント構成」を前提にした延伸であり、
  第6.2節で述べた「セグメント数が多く・かつ極端に広いターゲティング」のケースは
  縮小版では検証できていない（第7節参照）。

---

## 7. 縮小版ゆえに確かめられていないこと（正直に列挙）

- **ヒープの実測**: 第5節の通り、GCノイズに埋もれて実測できなかった。第6節の数値はすべて計算であり、
  実際にJVMがそれだけのメモリを確保することを直接確認したものではない。
- **50万から100万件規模での DB クエリ時間**: `complex_resolve_ms` は1万から5万件のレンジでしか
  測っていない。この規模ではDB往復コストが支配的であり、`Set` 演算のCPUコストがボトルネックに
  転じる分岐点（あるとすれば）は未確認。
- **本番相当の広さ・多さのセグメント構成**: 第6.2節で述べた「多数・広範囲なセグメント」の
  実データでの挙動は未検証（合成データの分布は試験用に設計したものであり、実際の広告主の
  ターゲティング傾向を代表しない）。
- **本番相当のJVM設定・並行アクセス**: 本試験は単一スレッド・単一リクエストでの `resolve()` 呼び出し
  のみを測定した。複数キャンペインの同時解決、本番のGC設定・ヒープサイズでの挙動は未確認。
- **AGE_RANGE / LOCALE / REGION_CITY / ORG_TYPE / DEVICE セグメント**: 参照列の確認のみで、
  実データでの評価は行っていない（第1節参照）。

---

## 8. 参考

- 評価対象サービス: `backend/src/main/java/com/mannschaft/app/advertising/campaign/service/AdAudienceResolver.java`
- 評価器一式: `backend/src/main/java/com/mannschaft/app/advertising/campaign/service/evaluator/`
- 測定 IT: `backend/src/test/java/com/mannschaft/app/advertising/campaign/perf/AdAudienceResolverScaledMeasurementIT.java`
- seed ヘルパー: `backend/src/test/java/com/mannschaft/app/support/perf/AdAudienceSeeder.java`
- 金型にした先行負荷試験: `docs/load-test/fanout-500k/findings.md`（CMP-001）
