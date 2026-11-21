<!-- `backend/.claudecode.md` §30 の本文（逐語移設）。節番号・見出しは元のまま。 -->
[← backend/.claudecode.md（索引）に戻る](../../../backend/.claudecode.md)

## 30. アーキテクチャ番人テスト（ArchUnit FreezingArchRule）

`com.mannschaft.app.common.architecture` パッケージには、ドメイン境界・DB設計・認可の原則
（本ドキュメント冒頭のアーキテクチャ思想 / 認可根治戦役）を機械的に強制する ArchUnit テストが
配置されている。代表例:

| テスト | 検査内容 | 凍結ストア UUID |
|---|---|---|
| `AuthzControllerGuardArchTest` | 公開Controllerエンドポイントの認可シグナル有無（認可根治戦役 Wave4） | `9ed4737d-c74f-4374-923e-4663d3c9e256` |
| `CrossDomainEntityImportArchTest` | クロスドメイン Entity 直接参照禁止（D-1） | `584c3a46-b9c1-4cc2-bf74-e0a18eab1bef` |
| `CrossDomainTransactionalArchTest` | 越境 `@Transactional` 禁止（D-3） | `f14374b1-655e-4df2-8e82-2d79c8df9174` |
| `EntityUuidV7ConventionArchTest` | 新規テーブルの `UuidV7Entity` 継承（D-2b） | `2c0ba995-682e-4f80-a5a5-f68c835b720d` |
| `CrossDomainRepositoryDependencyArchTest` | 越境 Repository 依存禁止（D-5。D-3 の `@Transactional` 前提を外した一般化） | `427c445d-37ce-4d6e-b095-a1733efe209f` |
| `ControllerEntityResponseArchTest` | Controller の JPA Entity レスポンス禁止（D-6。戻り型のジェネリクス入れ子まで走査） | **ストア無し（恒久ルール・違反0件運用）** |
| `JsonRequestBodyCreatorArchTest` | リクエストペイロード DTO がバインダから実体生成できること（D-7。`@RequestBody`/`@RequestPart`/`@ModelAttribute` から到達する型＋フィールド閉包） | **ストア無し（恒久ルール・違反0件運用）** |
| `SelfScopedEndpointMarkerGuardTest` | `@SelfScopedEndpoint` 付与要件の強制（根拠の記述・Controller への付与・**契約テストの実在**）。ArchUnit を使わないソース走査番人 | **ストア無し（免除リストも無し）** |
| `ScheduledBatchGuardTest` | `@Scheduled` の規約 5 点（`@SchedulerLock` 必須 / `lockAtMostFor` 明示必須 / `@BatchEndpoint` 必須 / 短周期バッチの `lockAtMostFor` > 起動間隔 / **プリミティブ戻り値禁止**。下記 §30.1） | **ストア無し（恒久ルール）** |

`common/architecture` には ArchUnit を使わないソース走査型の番人も同居する（凍結ストアを読み書きしない
ものは `--tests` での絞り込み実行をしても凍結ストアは壊れない）:

| テスト | 検査内容 |
|---|---|
| `ErrorCodeHttpStatusDeclarationGuardTest` | ErrorCode が Javadoc で宣言した HTTP ステータスが `GlobalExceptionHandler.ERROR_CODE_STATUS_MAP` 経由で実際に返るか（宣言と実挙動の乖離検知） |
| `IntentionallyPublicMatcherGuardTest` | `@IntentionallyPublic` が引用する **matcher 式**が `SecurityConfig` に実在し permitAll されていること |
| `BatchMarkerAnnotationGuardTest` | バッチ例外マーカー（`@PodLocalScheduled` / `@BatchEndpointExempt`）の付与要件（理由リテラル・**Javadoc 併記**・`@Scheduled` への付与）。Javadoc はバイトコードに残らないためソース走査で検証する |
| `FlywayTimestampNamingGuardTest` | Flyway 命名（`V{major}.{yyyyMMddHHmmss}`）とバージョン重複（§18） |
| `ArchUnitFreezeStoreIntegrityTest` | 凍結ストアの行数改竄検知（下記） |

#### ErrorCode の HTTP ステータス宣言番人（2026-07-30〜）

`ERROR_CODE_STATUS_MAP` に**未登録のコードは `Severity` 既定（WARN=400 / ERROR=500）へ
フォールバックする**。認可の是正では「不在も越境も同一コードに畳んで 404 を返し存在を秘匿する」
設計を採るが、これはステータスまで 404 に揃って初めて成立する。登録漏れがあると Javadoc・
設計書・契約テストの記述と実挙動が乖離した状態（宣言は 404、実挙動は 400）になる。
認可根治戦役の中でこの見落としが繰り返されたため、`ErrorCodeHttpStatusDeclarationGuardTest` が
機械的に検出する。判定は 2 ルール:

1. **宣言ステータス一致** — ErrorCode 定数の直上コメントが HTTP ステータス番号を明示していれば、
   実際に解決されるステータスがそれと一致しなければならない。
2. **存在秘匿の実効性** — コメントが「存在秘匿」`IDOR` `BOLA` を含み、かつステータス番号を
   明示していない場合は 404 または 403 に解決されなければならない。

全 ErrorCode 定数（2,000 件超）の登録を必須にはしない。大半は入力値不正で既定 400 が正しく、
全数登録を要求すると 1,000 件超の免責リストが必要になり無審査で膨れる baseline に堕するため。
上記 2 ルールは**ツリー内の宣言そのものを検証する**ので免責リストを必要としない。
例外リスト `DECLARATION_EXCEPTIONS` は 1 箇所に集約し、各エントリに理由を必須とする
（判断保留は「暫定・要再判断」と明記）。本テストはソースを読み取るだけで何も書き換えないため、
`--tests` 絞り込み実行で自壊しない。

**`@IntentionallyPublic` の引用規約（2026-07-30 改訂）**: 公開根拠は**行番号ではなく matcher 式**で引用する。

```java
@IntentionallyPublic("/api/v1/public/stats")
```

旧規約は `SecurityConfig.java:88 — ...` と**行番号**を引用していたが、`SecurityConfig` に 1 行挿入される
たびに全部ずれる。実測の結果、**行番号引用 35 件のうち 34 件が既に別の行を指していた**（「なぜ公開して
よいのか」を追う唯一の手掛かりが静かに嘘になっていた）。matcher 式は腐らず、Ctrl+F で辿れ、番人が
機械的に突き合わせられる。詳細は `docs/security/01_authorization_baseline.md` §3.0。
**公開してよいと判断した理由の Javadoc 記述は本体であり、必ず残すこと。**

D-6 / D-7 を除き `FreezingArchRule`（`freeze.refreeze=false`）で運用しており、既存の未解消違反は
`backend/src/test/resources/archunit_store/` 配下に凍結（免責）し、**新規に追加される違反のみ**
fail させる方式を採る。D-6 / D-7 は導入時点で違反0件のため凍結を持たない素の ArchRule であり、
違反が1件でも出たら即 fail する（偽陰性ゼロ証明のメタテストは
`ControllerEntityResponseConditionTest` ＋ `fixtures/D6*` /
`JsonRequestBodyCreatorConditionTest` ＋ `fixtures/D7*`）。
D-7 はさらに `JsonRequestBodyCreatorRuntimeProofTest` が**本番同等設定の実 `ObjectMapper` で
デシリアライズを走らせ**、「番人が弾く形は本当に `InvalidDefinitionException` で落ちる／通す形は
本当に往復できる」の両方向を実測固定している（構造条件だけを精緻化して実挙動と乖離するのを防ぐ）。
D-7 の判定は `@JsonCreator` の**存在**ではなく**採用可能な creator の本数**で見る
（`mode = DISABLED` は打ち消し、properties-based が 2 本以上なら衝突で 500）。
またフォーム経路の根は「注釈が 1 つも無い引数」ではなく「**バインド元を決める注釈が無い**引数」で
判定する（`@Valid` だけが付いた複合型引数は暗黙 `@ModelAttribute` として実体化されるため）。

#### §30.1 バッチ（`@Scheduled`）を書くときの規約（2026-08-05〜・番人あり）

本番は複数 Pod で動くため、**`@Scheduled` は何もしなければ Pod 数だけ同時に走る**
（＝二重通知・二重課金・二重集計）。従来この規約は `ShedLockConfig` の Javadoc 一覧という
「人間の善意」で維持されており、付け忘れても CI は緑のままだった。
`ScheduledBatchGuardTest` が次の 5 点を機械的に強制する:

1. **`@Scheduled` ⇒ `@SchedulerLock` 必須**（例外は `@PodLocalScheduled` のみ）
2. **`@SchedulerLock` ⇒ `lockAtMostFor` 明示必須**
   — 未指定は `@EnableSchedulerLock(defaultLockAtMostFor = "30m")` への暗黙依存。
   既定 30m は数秒ワーカーには長すぎ（Pod 異常終了でバッチが 30 分停止）、
   1 時間かかる夜間集計には短すぎる（処理中に他 Pod が二重起動）。
   **そのバッチの最大実行時間を書き手に必ず考えさせる**のが目的。
3. **`@Scheduled` ⇒ `@BatchEndpoint` 必須**（例外は `@BatchEndpointExempt` のみ）
   — 無いと名前で起動できず実行履歴も残らないため、実機検証も障害調査もできない。
4. **短周期バッチ（起動間隔 1 時間以下）は `lockAtMostFor` > 起動間隔**（2026-08-06 追加）
   — 間隔以下だと、1 回の実行が `lockAtMostFor` を超えた時点でロックが失効し、次の起動と重なる。
   **同値が最も危険**（実行がわずかに超過しただけで重なる）。下記「`lockAtMostFor` の決め方」の
   1 つ目の項目を、番人が機械的に強制するようにしたもの。
   起動間隔は `cron` / `fixedRate` / `fixedDelay`（文字列版含む）の 3 形式すべてから算出し、
   cron は Spring の `CronExpression` に実際の発火時刻を列挙させて**最小**発火間隔を採る。
   **算出できない場合（既定値の無い `${prop}` プレースホルダ等）は安全側に倒して落とす** ——
   通してしまうと「cron を外部プロパティへ追い出す」が番人の抜け道になるため。
   `${prop:0 0 3 * * *}` のように**既定値付き**で書き、本番の起動間隔をコードから読めるようにすること。
   **日次・週次・月次は対象外**（次の起動まで 24 時間以上あり重なりが起きない。むしろ 24 時間超の
   ロックは Pod 異常終了時にバッチを丸一日停止させるため有害）。
5. **`@SchedulerLock` 付きメソッドはプリミティブ戻り値禁止**（2026-08-11 追加・issue #2724）
   — ShedLock はロック対象メソッドの戻り値をプロキシで包む都合上、プリミティブ型を返すメソッドを
   ロックできず `LockingNotSupportedException` で**毎回必ず失敗する**。`int` を返していた
   `ReservationPendingExpireBatchService` は、CI 緑・起動も成功のまま本番で 1 度も走っていなかった。
   `void` か参照型（`Integer` 等）にすること。件数を返したい場合は `Integer` を使い、
   併せて `log.info` でも件数を出す（ShedLock がロックを取れずスキップした回は `null` が返る）。

**例外マーカー 2 種**（`com.mannschaft.app.common.batch`）:

| マーカー | 免除するルール | 付与が正当な場合 |
|---|---|---|
| `@PodLocalScheduled` | 1（`@SchedulerLock`） | ロックを掛けると**かえって壊れる**もの。Pod ローカルのメモリバッファ flush（敗者 Pod のバッファが永久に残る）、Pod ごとの死活監視（1 Pod しか監視されなくなる） |
| `@BatchEndpointExempt` | 3（`@BatchEndpoint`） | 数秒間隔の高頻度ワーカー。1 回ごとに履歴を書くと日次・月次バッチの記録が埋没する |

いずれも **`value()` に理由（文字列リテラル）＋対象メソッドの Javadoc が必須**であり、
二次番人 `BatchMarkerAnnotationGuardTest` が機械的に検証する（**免除リストは無い**）。
理由なき付与は「実装漏れの永久凍結」と区別がつかず、番人を骨抜きにするバックドアになる
（`@IntentionallyPublic` と同じ規約思想）。

**`lockAtMostFor` の決め方**（2026-08-05・33 件の一斉付与で確立）:

- **起動間隔と同値にしてはならない**（**ルール 4 として番人が強制**。2026-08-06〜）。
  同値だと 1 回の実行が間隔を超えた瞬間にロックが失効し、次回起動と重なる。
  短周期バッチは**間隔の 3 倍**を基本とする
  （`ReservationPendingExpireBatchService`: 5 分間隔に対し 15 分）。
  2026-08-06 の一斉是正では、5 分以下は間隔の 3 倍、15 分〜1 時間は間隔の 2 倍を採った
  （倍率を上げすぎると Pod 異常終了時の停止時間がそのまま延びるため、
  「重なりを構造的に不可能にする最小限＋余裕」に留めている）。
- 日次・週次・月次バッチは間隔ではなく**最悪ケースの処理時間**から決める。
  1 回あたりの打ち切り上限（例: `BATCH_LIMIT`）や 1 件あたりの外部 I/O 回数から見積もり、
  **見積もりの根拠をコード上のコメント／Javadoc に日本語で残す**こと
  （例: 上限 50000 件 × S3 削除 0.2 秒 ≒ 2.8 時間 → 3 時間）。
- `lockAtLeastFor` は同時起動した Pod どうしの取り合いを防ぐ最小保持時間。
  秒で終わる短周期バッチほど必要（既定は短周期 30 秒 / 日次以上 1 分）。
- `name` は `shedlock` テーブルの主キーであり**全バッチで一意**。付与前に既存名を grep で確認すること。

**多重実行対策はロックだけで完結しない**。`@SchedulerLock` は同時実行を減らすが、
ロック期限切れ・手動起動との競合まで塞ぐものではない。
とくに使用量・カウンタの **read-modify-write**（`used_bytes`・`usage_count` 等）は、
「集計してから引く」のではなく**「実際に削除できた行数」を条件付き DELETE の戻り値から得て引く**
（claim-then-act）。行削除は行ロックで直列化されるため、減算量の合計が必ず実数と一致する
（実例: `BlogMediaUploadRepository#deleteOrphanById`、`QuickMemoTagLinkRepository#deleteByMemoIdInAndTagId`）。

**凍結ストアは使わない**。`--tests` 絞り込み実行での凍結ストア破壊事故を新たに持ち込まないため、
および本番デプロイ前の今こそ例外を最小化すべきであるため、例外は上記マーカーだけで表現する。

**`@Repeatable` の罠**: `@Scheduled` は `@Repeatable(Schedules.class)` であり、1 メソッドに
2 つ以上書くと javac は `@Scheduled` を直接付けず `@Schedules` コンテナに包む。
`areAnnotatedWith(Scheduled.class)` だけの実装は複数スケジュール指定のバッチを丸ごと取り逃すため、
番人は `@Schedules` も対象に含めている（メタテスト `ScheduledBatchGuardConditionTest` が fixture で実証）。

#### 認可番人の「監査済マーカー」4 種と `@SelfScopedEndpoint` の運用ルール

`AuthzControllerGuardArchTest` の認可シグナル (C) として認識される監査済マーカーは
`com.mannschaft.app.common.security` に 4 種ある。**主張の内容ごとに分離**されており、
実態と異なるマーカーを貼ることは誤った証跡となるため禁止する（横断方針は
`docs/security/README.md` §4.2）。

| マーカー | 主張 | 付与できる対象 | 付与時の必須条件 |
|---|---|---|---|
| `@SelfScopedEndpoint` | **他人のデータへ構造的に到達できない**（検索・更新の対象が認証主体に束縛され、リクエストで他人の識別子を指定する余地が無い） | **メソッドのみ** | `value()` に根拠を記述（必須属性）＋**対応する契約テストの実在** |
| `@AuthorizedInService` | 白名簿クラスを介さず Service 内の別方式で認可済み（webhook 署名検証・capability トークン等） | メソッド / クラス | 認可の実施箇所と方式を Javadoc に明記 |
| `@AuthorizedByPathConfig` | `SecurityConfig` のパス単位 `hasRole()` 等で宣言的に強制済み | メソッド / クラス | 根拠となる `requestMatcher` を Javadoc に明記 |
| `@IntentionallyPublic` | `permitAll()` 配下で意図的に無認可公開 | メソッド / クラス | `permitAll()` 行＋公開してよい理由を Javadoc に明記 |

**`@SelfScopedEndpoint`（2026-07-30 新設）を貼るときの手順**:

1. **`@AuthorizedInService` を流用しない**。「到達不能」と「どこかで認可済み」は別の主張である。
   過去の波では自己スコープ EP に `@AuthorizedInService` を貼った例があるが、**今後その転用は禁止**。
2. `value()` に**その場で読める文字列リテラル**で根拠を書く。
   「どのクラスのどのメソッドが、認証主体の何を検索条件に使っているか」まで書き下すこと
   （空文字・空白のみ・定数参照・実質のない一言は番人が拒否する）。
3. **契約テストを用意する**。当該エンドポイントを踏み、無関係な他ユーザーからは対象データへ
   到達できないことを固定する契約テスト（`*ScopeContractIT` 等）を追加し、その Javadoc または
   `@DisplayName` に `<Controller 単純名>#<メソッド名>` を明記する（既存の契約テストへの追記でも可）。
   このリンクの実在を番人 `SelfScopedEndpointMarkerGuardTest` が機械的に要求する。
   `common/architecture` パッケージ（番人・凍結ストア台帳）への記述は証跡として数えない。
4. **クラス単位には貼れない**（`@Target(METHOD)`）。到達不能性はエンドポイント単位の性質であり、
   自己スコープ EP とリソース ID を受け取る EP は同一 Controller に併存するため。
5. **要件を満たせないなら貼らない**。本マーカーに**免除リストは無い**。実効的な認可
   （`AccessControlService` / `*AccessGuard` 等）を敷設するか、凍結ストアに残して
   未返済として可視化し続けること（「操作者 == 所有者」の直接比較で拒否している EP は
   到達可能性自体が存在するため本マーカーの対象外）。

番人 `SelfScopedEndpointMarkerGuardTest` は `FreezingArchRule` を使わない**ソース走査**方式で、
ファイルを読み取るだけで一切書き込まない。よって後述の「`--tests` 絞り込みで凍結ストアが壊れる」
事故を自ら引き起こすことはない。判定の既知の限界（名指しがコメント／文字列でも成立すること）は
同テストの Javadoc に明記してあり、**テストが実際に到達不能性を assert しているかは検分で見る**前提。

#### D-7 が防いでいる事故（POST/GET が「常時 500」になる DTO）

**JSON 経路**: Jackson は**コンストラクタが複数**あるとデシリアライズ用のものを一意に決められない。
そこへ「引数無しコンストラクタ無し」が重なると、`InvalidDefinitionException`（no suitable creator）で
デシリアライザの構築自体に失敗する。Spring はこれを **`HttpMessageConversionException`** として投げるが、
これは 400 にマップ済みの `HttpMessageNotReadableException` とは**別系統**であり
`GlobalExceptionHandler` に個別ハンドラが無いため、汎用ハンドラに落ちて
**クライアントが何を送っても当該エンドポイントが 500（`COMMON_999`）** になる。
エラーコードの付かない素の例外なので `ERROR_CODE_STATUS_MAP` / `Severity` の整備では直らない。

**フォーム経路**: `@ModelAttribute`（および Controller メソッドの無注釈複合型引数）は
`ModelAttributeMethodProcessor` が `BeanUtils.getResolvableConstructor` で実体を作るが、これは
「Kotlin primary → **宣言コンストラクタがちょうど 1 本** → 引数無しコンストラクタ」の順にしか解決しない。
よって同じ形（2 本以上＋引数無し無し）だと `IllegalStateException` で同じく 500 になる。
**フォーム経路では `@JsonCreator` は効かない**（バインダが Jackson 注釈を見ない）。

Mock ベースの Service UT は DTO を直接 `new` するため**原理的に検出できない**。実際
`chat.dto.SendMessageRequest`（PR #2033）と `bulletin.dto.CreateThreadRequest`（PR #2503）で、
**同一改修の片方だけ直る**という形で 2 度再発している。

**是正の金型**は `chat/dto/SendMessageRequest` — 完全コンストラクタに `@JsonCreator` を付け、
各引数に `@JsonProperty("...")` を付ける。独自の工夫はせずこの型に揃えること。
コンストラクタが 1 本だけの DTO は `-parameters`（`build.gradle.kts:401`）＋ `JacksonConfig` が明示登録する
`ParameterNamesModule` により暗黙 creator になるため `@JsonCreator` は不要
（**2 本目を足した瞬間に壊れる**、というのが病因）。

検査対象は**命名でも形状でもなく到達可能性**で決めている。`*Request` 命名で絞ると、命名該当 932 クラスの
うち 19 件（`CspReportRequest` 等・どこにもバインドされない）を誤検出し、逆に閉包に含まれる 112 型
（`AddContactRequestBlockBody` 等・`*Request` で終わらない）を取り逃す。形状だけで絞ると、
バインドされない 15 件（`BusinessException` / `TokenResponse` / `LoginSuccessEvent` 等）が全件偽陽性になる。

**`record` は JSON 経路では除外する**（Jackson 2.12+ が正準コンストラクタをネイティブ解決するため）。
実在例 `village/dto/MeetupCreateRequest` は record かつコンストラクタ 2 本で `@RequestBody` に
バインドされており、除外しないとこれが偽陽性になる。フォーム経路では
`getResolvableConstructor` が record を特別扱いしないため除外しない。

### 本番全体の取り込みは共有ホルダ `ProductionClasses.get()` のみ（CMP-261002-1606）

全量 CI の shard 5 が `Java heap space` で毎回落ちていた。原因はテストクラスがそれぞれ
`new ClassFileImporter().importPackages("com.mannschaft.app")` で本番全体を取り込み、
static フィールドで何コピーも握り続けていたこと（`@AnalyzeClasses` は ArchUnit の ClassCache で
共有されるが、手動の `ClassFileImporter` 呼び出しは共有されない）。

- **本番全体（`com.mannschaft.app`、テストクラス除外）の取り込みは
  `com.mannschaft.app.common.architecture.ProductionClasses.get()` だけを使う**。JVM 内で1回だけ
  取り込み、以後は同一インスタンスを返す（失敗時は初回の例外をそのまま、以後は初回例外を cause に持つ
  `IllegalStateException` を再送し再試行しない。null・空の結果も失敗扱い）。
- 本番の一部パッケージが要るときも、原則ホルダから引いてルール側（`that().resideInAPackage(..)`・
  FQN 指定）で絞る。クラスを列挙する `importClasses` と、本番の一部・fixture パッケージに限った
  `importPackages` は許可。`importPath`/`importClasspath` 等の場所単位の取り込みは禁止。
- **テストクラスの `JavaClasses` 型 static フィールドは禁止**（fixture だけの取り込みでも）。
  instance フィールド（必要なら `@TestInstance(PER_CLASS)`）かローカル変数にする。
- 番人 `ProductionClassImportGuardTest` がテスト出力の .class を ASM で走査し、上記違反を機械的に
  拒否する。凍結ストア・許可リストは持たない（違反は根治で返済する）。

### ArchUnit を使うテストは `@Tag("archunit")` を付け、`archTest`（別 JVM）で走らせる（CMP-261002-1606）

MAT 実測で、shard 5 の OOM は ArchUnit の本番取り込み（`@AnalyzeClasses` の ClassCache 約 0.58GB ＋
`ProductionClasses` 約 0.49GB）が Spring テストコンテキスト（15 個 1.44GB）と**同じワーカー JVM** に
乗っていたことが上乗せの一因だった。ArchUnit は Spring を要しないため、別 JVM に分けた。

- **ArchUnit（`com.tngtech.archunit.*`）または `ProductionClasses` を参照するテストクラスはタグ必須**
  （テストツリー内のヘルパ経由の参照も推移的に対象）。Jupiter のテストクラスは
  `@Tag(ArchUnitTestTag.ARCHUNIT)`、`@AnalyzeClasses` のクラスは `@ArchTag(ArchUnitTestTag.ARCHUNIT)`
  （ArchUnit エンジンは Jupiter の `@Tag` を読まない）。
- **タグ付きテストは Spring のテストコンテキストを使わない**（`@SpringBootTest`/`@WebMvcTest`/
  `@DataJpaTest`/`@ContextConfiguration`/`AbstractMySqlIntegrationTest` 継承 等）。
- **タグ付きテストはエンジンで2系統に分かれ、実行系統も異なる**（タグだけが唯一の走行経路ではない）。
  - `@Tag(ArchUnitTestTag.ARCHUNIT)` の junit-jupiter クラスは、通常の `test` がタグ `archunit` を
    除外することで `test` から外れ、専用タスク **`archTest`**（heap 3g・forkEvery=0・
    maxParallelForks=1 の 1 JVM・shard 分割なし・jacoco 無効）だけが走らせる。`check`（= `build`）に
    載り、CI は `backend-ci.yml` / `backend-deploy.yml` / `backend-nightly-full.yml` の専用ジョブ
    `arch-test`（表示名 "ArchUnit tests (separate JVM)"）で 1 回だけ走る（`backend-ci.yml` は集約
    ゲート `Compile & Test` の needs、deploy は `build-and-deploy` の needs）。ログ
    `[test-count] archTest: 実行 N 件` で走ったことを確認でき、0 件ならタスクが失敗する。結果 XML は
    `build/test-results/archTest/`（artifact 名は `archtest-results-xml-*` で、
    `regen-shard-weights.sh` の材料に混ざらない）。
  - `@AnalyzeClasses` のクラス（`@ArchTag(ArchUnitTestTag.ARCHUNIT)` 付与。ArchUnit 専用の **archunit
    エンジン**で実行され、Jupiter の `@Tag` は読まない）は専用 CI ジョブを持たない。`archunit` エンジン
    だけを含める別の `Test` タスク **`archUnitTest`** が走らせ、`tasks.named<Test>("test") {
    dependsOn(archUnitTest, ...) }` により通常の `test`（各 shard）の依存タスクとして、shard 分割を
    適用せず全ルールを Spring とは別 JVM で実解析する。heap 4g・forkEvery=0・maxParallelForks=1
    とし、全 compiled class の選別で多数の JVM を再起動しない。通常 `test` の180 class毎のfork・
    parallelは維持し、mainと同じ8 shardに割り当てる。CI の専用ジョブ `arch-test` では
    `archTest` しか起動しないため **`@AnalyzeClasses` 側はここでは走らない**（各 shard の `test` 実行
    に埋め込まれて走る）。
- タグ付きクラスは `shard-weights.properties` に載せない（通常 `test` の shard 貪欲割当が、走らない
  秒数を数えて偏るため）。導入時に 28 クラス（291 秒）を除去した。
- 番人 `ArchUnitTestTagGuardTest`（タグなし・通常 `test` で走る）がテスト出力の .class を ASM で走査し、
  タグ漏れ・タグ付きの Spring 使用・重み表への混入を拒否する。ASM で生成した見本による自己検証付き。
- ローカル確認: junit-jupiter 側（`@Tag`）は
  `./scripts/gradle-turnstile.sh ./gradlew archTest --tests "<完全修飾名>"`。
  **`@AnalyzeClasses` のクラスを `archTest --tests` に指定すると実行されずタスクが失敗する**（`archTest` は
  `excludeEngines("archunit")` のため archunit エンジンのクラスは 0 件のまま無視されるが、
  `build.gradle.kts` の `archTest` 専用 `doLast`（`executedTestCount <= 0` で `GradleException`）が
  0 件実行を検知して失敗させるため、素通りはしない）。`@AnalyzeClasses` 側を確認するときは
  `./scripts/gradle-turnstile.sh ./gradlew archUnitTest` を使う（`--tests` 絞り込みは凍結ストアを
  破壊するため避ける。後述「凍結ストアは `--tests` 絞り込み実行で破壊される」節参照）。

### テストコンテキストキャッシュ上限 10（CMP-261002-1606・PR #3620）

MAT 実測（run 37196327170）で、shard 0 の OOM 時ヒープの 59%（2.48GB）を Spring テストコンテキスト
17 個（平均約146MB・最大177MB）が占めていた。`spring.test.context.cache.maxSize` は未設定で既定 32。
テスト構成の種類が多く、1 JVM に十数個が溜まると -Xmx4g を超える。`backend/build.gradle.kts` の
`tasks.withType<Test>` で `systemProperty("spring.test.context.cache.maxSize", 10)` を既定とし、
`-Pspring.test.context.cache.maxSize=N` で上書き可能にした（10 × 最大177MB ≒ 1.8GB で 4g に余裕、
超過分は LRU で close される。archTest は Spring を使わないため無害）。番人
`SpringTestContextCacheMaxSizeGuardTest`（`common/cache`）が設定がテスト JVM に届いていることを検証する。
これはあくまで応急の上限で、根治は独自 `@MockitoBean` 等を共通構成へ寄せてコンテキストの種類自体を
減らすこと（CMP-261004-2047 で追跡）。

### ⚠️ 凍結ストアは `--tests` 絞り込み実行で破壊される

`./gradlew test --tests "SomeOtherTest"` のようにテストクラスを絞り込んで実行すると、
対象の ArchUnit テストが走らない（または解析対象が絞られる）ため、`FreezingArchRule` が
「今回検出されなかった違反」をストアから誤って削除して書き戻してしまう事故が過去に複数回
発生した。免責リストから違反が消えるのは一見「改善」に見えるが、実態は**解消されていない
認可の穴・アーキテクチャ違反が番人の監視対象から静かに脱落する**という重大な後退である。

**対策**:
1. **ArchUnit 番人テストを含むテストクラスを `--tests` で絞り込んではならない**。動作確認が
   必要な場合は必ずフルの `./gradlew test`（時間がかかる場合は `./gradlew compileTestJava` に
   留め、実行自体は CI に委ねる）を使うこと。
2. 上記事故を機械的に検知する番人 `ArchUnitFreezeStoreIntegrityTest`
   （`common/architecture`）が、7つの凍結ストアファイルの**行数（＝凍結された違反件数）**を
   定数 `EXPECTED_LINES_*` を**上限**として照合する。上限超過は fail、上限以下は pass とし、
   行の重複・自然順の整列崩れ・七ルールそれぞれの形式不正を拒否する。
3. **根治して凍結ストアの行数が本当に減った場合の運用手順**:
   - `git diff backend/src/test/resources/archunit_store/<UUID>` で解消された違反の内容を確認する
   - 返済PRは減少行と返済内容を本文で照合し、定数を変更しない
   - 締めレーンL-SHIMEが週1回以上（または3ロット完了時）に減少原因を突き合わせ、実測行数へ
     上限を引き下げる。履歴は `docs/architecture/archunit_freeze_history.md` へ記録する
4. `.github/CODEOWNERS` で `backend/src/test/resources/archunit_store/` 配下をオーナーレビュー
   必須に設定している（意図しない変更が無レビューで混入しないための最終防衛線）。

**各runnerの後段保護**: `archUnitTest` の finalizer は専用
`archUnitFreezeStoreIntegrityTest`（Jupiter、既存Integrityの2case）を起動する。
通常 `test` は両taskに依存し、Integrityを重複実行しない。専用guardは通常shard割当から外し、
8runnerで2caseずつ計16caseを要求する（2026-11-18 に 6 runner・12case から変更。CMP-261111-0508）。SelfTestは `archunit` タグで全 `archTest` に移し、
通常shardで重複させない。Mechanismの試練も含め、専用guardの16caseとは区別する。
Arch解析とguardは正本storeをinputsに持ち、cache/up-to-dateによる省略を許さない。
guardだけはCLI `--tests` のincludeを解除し、必須class欠損は例外とする。
NO-SOURCE、XML欠落、skipは合格ではない。Archが失敗した後にguardが成功しても全体は失敗のまま。

登録はServiceAPIを含む7 descriptionsの閉集合で、`stored.rules` の完全key集合・UUID対応・UUID一意と
重複キー拒否・実ファイル集合・各storeの上限と形式を検査する。`allowStoreCreation=false`、`refreeze=false` を維持する。
新rule導入時は設計・初回違反の裁可・UUID/store/guard登録を同じ変更で検分し、初回生成だけを
隔離した明示作業として行う。常時creation/refreezeを有効にして新違反を無断凍結しない。
既存違反の解消時にstoreを縮小するArchUnit本来のchip-awayは維持する。

D-1/D-3/D-5はDependencyごとの既存prefixに依存descriptionと `[occurrence=N]` を付けて全行を一意にする。
同一行の同callもdedupせず保持し、既定fuzzy照合で無視される行番号・`$`後の数字を除いた群で通番を振る。
L1-Fの一度限りの形式移行は、L1-G0完了後、同三storeへの他返済と排他して行う。
2026-10-22の初回承認に限り、旧文言の既知Set照合で隠れていた既存群内178件（D-1+119/D-3+33/D-5+26、
削減0・新class-pair0）を全件説明し、三上限を2156/1325/1876へ補正する。承認増分を除く旧prefix射影頻度Mapは
移行前と全件一致を要求し、他4store・UUID・`stored.rules`・他4上限・判定/除外を変えない。詳細は
`docs/architecture/modular_monolith_boundary_debt_repayment.md` §7.4 を正とする。

**検出範囲の既知限界**: D3/D3TのTX入口判定はSpringの`@Transactional`注釈を対象とし、
programmatic `TransactionTemplate` の実TXを網羅検出しない。AccountPurgeServiceは既承認の
per-user REQUIRES_NEW修正でgdpr→auth責務を維持したまま、旧D3の38occurrenceとD3Tの12occurrenceが
注釈入口scopeから外れた。このscope50の縮小は越境処理撤去とは分類しない。D5のrepository直接依存検査と既設計裁可を
引き続き照合する。未知縮小を期待値だけで合わせず、新programmatic TX変更も実境界・caller・
裁可を検分する。今回のroot裁可はこの既知範囲の記録であり、新waiverやrule範囲変更ではない。

**診断原本とstore更新の分離**: backend CIの既存XML artifactは各runnerのtest終了後の
`src/test/resources/archunit_store` 8file（UUID7＋stored.rules）も収録する。固定正式HEADのgit blobを
before、同runner ZIPをafterとして8SHA・UUID対応・7counterを確認する。各runnerの `archUnitTest` XMLは
`backend/scripts/verify_archunit_execution.py` が現Java sourceの行頭 `@ArchTest` から導出した
クラス別件数と照合し、全クラス一致・unique case・failure/error/skip 0を要求する。
固定件数を永続hardcodeせず、0件・部分実行・重複を失敗にしてJSONをCI診断成果物へ残す。
各ルールが実際に解析されたrunnerのafterを対応付け、未実行storeの
未変化をruntime解析成功へ転用しない。timeout等でartifactが無ければ未検証とする。
旧130の真の原因是正とscope50（D3 38＋D3T 12）の注釈入口変化を分け、合計180を越境180解消とは扱わない。
正式1e8の担当runner原本と原因検分に基づくstore/期待数更新を、新HEADの実解析GREENへ転用しない。
fresh原本の原因検分後にだけstore/期待数を
別の承認で更新する。収録パス追加でZIPの共通親rootが変わり得るためXMLはexact suiteを照合する。

ローカルのshard指定付き全解析確認は `./scripts/gradle-turnstile.sh ./gradlew -Pshard.total=6 -Pshard.index=3 archUnitTest archUnitFreezeStoreIntegrityTest`、
続いてbackendで `python3 scripts/verify_archunit_execution.py --output build/ci-diagnostics/archunit-execution.json` を実行する。
`--tests` は指定せず、全解析と後段Integrity 2 caseを確認してからstore 8fileの差分を照合する。

専用task導入時のGradle8.14.4実証には、通常6runnerのArch→guard→業務順序、直接Arch入口、
cache/up-to-date再実行、CLI選択、欠損class/NO-SOURCE、ArchFAIL＋guardPASSでも全体exit非0を含める。
`onlyIf` の必須class検査は欠損でthrowしfalse(SKIP)を返さない。onlyIf→SkipEmptyの実順序と
predicate例外時の非0は配布bytecodeまたは隔離試練で確認し、最新API説明だけで実測合格にしない。

---
