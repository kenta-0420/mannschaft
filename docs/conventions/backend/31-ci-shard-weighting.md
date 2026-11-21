<!-- `backend/.claudecode.md` §31 の本文（逐語移設）。節番号・見出しは元のまま。 -->
[← backend/.claudecode.md（索引）に戻る](../../../backend/.claudecode.md)

## 31. CI テストシャードの重み付け振り分け

`backend-ci.yml` の test ジョブは **8 shard**（matrix `0..7`、`env.SHARD_TOTAL: "8"`）に分割している。
`backend-nightly-full.yml` と `backend-deploy.yml` も同じ 8 shard で、3 workflow の `SHARD_TOTAL` と matrix は必ず揃える（2026-11-18・CMP-261111-0508 で 6 → 8。BE テスト CI の時間切れの是正。**step 上限 85/105 分・job 上限 100/120 分・nightly の step 105・job 120・deploy の現行値は変えていない**）。分割は（`build-logic/src/main/kotlin/mannschaft.backend-conventions.gradle.kts` の
`-Pshard.total` / `-Pshard.index` フィルタ）。

CMP-261114-0713 の全 ArchUnit 解析・後段 Integrity を各 runner で必須化する変更を統合した現在は、
PR Check の上限だけを通常 step 95 分・job 110 分、migration 込み step 110 分・job 120 分とする。
上記の 8 shard 導入時の上限維持は当時の変更範囲を表し、この採択済み予算を撤回しない。
nightly と deploy の上限は上記の既存値を維持する。

### 振り分けアルゴリズム

- `backend/src/test/resources/shard-weights.properties`（クラス単位の実測秒数）が存在する場合:
  **貪欲法**（重い順に、その時点で合計時間が最小の shard へ割り当てる Longest Processing Time
  First）でトップレベルクラスを `shard.total` 分割へ割り付ける。理論上界は最適解の 4/3 倍。
- 割り付ける対象は「**実行対象のトップレベルクラス集合**」。コンパイル済み .class から、抽象クラス・
  テストメソッドを持たないヘルパー・jupiter の `@Tag("perf"/"archunit"/"er-diagram")` 付き（専用タスクが実行）を
  除き、ネストクラスはトップレベルへ寄せて数える（`CompiledTestClassScanner` / `ShardAssignment.executableTopLevelClasses`）。
  `@AnalyzeClasses` だけのクラスは `archUnitTest` が実行するため、**通常の `:test` の集合には含めない**
  （`archUnitTest` の割り当て集合は従来どおり含める）。
- **どのクラスも必ずただ 1 つの shard で走る**: 実行対象集合に入らなかったクラス（抽象・ヘルパー・走査の取りこぼし）も、
  割り当て表に無ければ従来の FQCN ハッシュ（`hashBucket`）で 1 shard に決定的に入れる。以前は「集合外はどの shard でも除外しない」
  としていたため、走査が取りこぼした本物のテストが全 shard で重複実行され、番人にも見えなかった（検分 P1）。
  取りこぼした場合の害は「LPT の重みを持たず重み表の未掲載にも数えられない」だけ。
- 走査（`CompiledTestClassScanner`）は JUnit の検出に寄せてある: `@Test` 等をメタアノテーションに持つ**テストソース内で宣言された**独自注釈
  （例 `@FastTest`）を数える。子が注釈なしで override した親のテストメソッドは数えず、private / static のテストメソッドも数えない。
  抽象クラスのネストにだけテストがある外側は数えない（具象のサブクラスが継承したテストは数える。`@Nested` の内側だけにテストがある外側は数える）。
  ライブラリ側で宣言されたメタ注釈などは読めず、集合から漏れることがあるが、上のとおり 1 shard で走るので安全で、
  害は重み表の未掲載に数えられない（＝既定重みで LPT に入らない）ことだけである。
- 重み表に載っていないクラス（新規テスト等）は**ハッシュではなく既定重み付きで同じ貪欲法に参加**する。
  既定重み = 重み表の値の**中央値**（件数が偶数なら中央 2 値の平均。重み表が空・全 0 なら固定 1.0 秒）。
  同じ重みは FQCN 昇順、合計が同じ shard は index の小さい方を選ぶため、**クラスの列挙順に依存しない**。
- 重み表ファイル自体が存在しない場合も、全クラスが既定重み 1.0 で割り付く。
- `shard-weights.properties` に負値・NaN・Infinity があると `loadWeights` がクラス名付きで例外にする。
- **プロファイル別の構成時計算**: 貪欲法は「実際にその CI 実行で走るクラス集合」に対して行う。
  `-PexcludeMigrationTests=true`（通常 PR の大多数）の場合は migration+Flyway クラス（旧XML集計で重いクラス群）を貪欲法の対象から事前に除外してから残りのクラスで均等化する。migration 込みの
  重みでそのまま最適化すると、通常 PR では migration クラスがまるごと除外されて残りだけが走る
  ため、逆にその残りが大きく偏る回帰を招く（実測: 最大/最小比 2.19 倍まで悪化した事例あり）。
- 割り付け・判定ロジックは `build-logic/src/main/kotlin/ShardAssignment.kt` の `ShardAssignment` オブジェクトに一本化されて
  おり、**実際のテスト実行時 `Test.exclude { }` フィルタと `verifyShardCoverage` 検証タスクの
  両方が同じ集合の作り方（`runnableTopLevelClasses`）・同じ割り当て（`assignShards`）・同じ述語関数
  （`ShardAssignment.shouldExcludeForAssignedShard()` / `shouldExcludeMigrationReplay()`）をそのまま呼ぶ**（パス正規化・bucket 判定・否定を丸ごと
  関数内に閉じ込め、呼び出し側で別実装の再計算や外側での `!` 反転を行わない）。フィルタが
  反転・全除外・多重割当・正規化破損のいずれに壊れても、検証は同じ壊れ方の影響を受けて確実に
  fail する構造。
- `verifyShardCoverage` タスクで、コンパイル済み .class ファイルの生パスに対して実際に検証
  できる: `cd backend && ./gradlew verifyShardCoverage -Pshard.total=8`
  （shardIndex = 0..total-1 の全パターンへ実際に述語を適用し、
  1. **コンパイル済みの全トップレベルクラス**（実行対象集合の外も含む）がちょうど1つの shard で実行されること（0=取りこぼし、2以上=重複割当）、
  2. migration 除外プロファイルでは migration+Flyway クラスがどの shard でも実行されないこと、
  3. 同一トップレベルクラスに属する複数 .class ファイル（ネストクラス）が同じ shard に乗ること
     （グルーピングは `ShardAssignment` を経由しない独立した文字列操作で行い、正規化バグの
     自己採点を防ぐ）
  の3点を、重み表あり/なし × migration除外あり/なしの**4プロファイル**（通常 `:test` の集合）と、`archUnitTest` の集合による 2 プロファイルで assert する）。
- CI では `backend-ci.yml` の `shard-coverage-guard` ジョブが 1 回だけ `verifyShardCoverage` を
  実行し、`gate`（必須チェック「Compile & Test」）の needs に含まれる。shard それぞれで
  重複実行はしない。同ジョブは `gradlew -p build-logic test`（`ShardAssignment` / `ShardBalanceGuard` / `CompiledTestClassScanner` の単体テスト）と
  `python3 -m unittest scripts/test_regen_shard_weights.py` も実行する。

### 番人（重み表の鮮度と shard 偏り）

`verifyShardCoverage` は上の取りこぼし検査に続けて `ShardBalanceGuard`（`build-logic`）を呼び、違反でタスクを失敗させる。
閾値は `ShardBalanceGuard` の定数 1 か所（テストもこの定数を参照する）。**閾値の変更は殿の判断事項**。

| 検査 | 閾値（定数） | 対象 |
|---|---|---|
| 重み表に未掲載の実行対象トップレベルクラス数 | `MAX_UNLISTED_CLASSES` = 100（ちょうどは合格） | full 集合で 1 回 |
| 予測 max/avg 比（LPT 割り当て後の shard 合計重みの最大 / 平均） | `MAX_IMBALANCE_RATIO` = 1.10（ちょうどは合格） | full と migration 除外の両集合 |

失敗メッセージにはプロファイル名（`full` / `migration-excluded`）と再生成手順 `backend/scripts/regen-shard-weights.sh` を含む。
未掲載が閾値を超えたら重み表を再生成する（日常的な数クラスの増減は既定重みが吸収する）。

### 導入の背景

安定ハッシュのみによる振り分けは実行時間を考慮しないため、shard 間で最長/最短の壁時計に
1.3〜1.8 倍のばらつきが生じていた（2026-08 実測）。CI 全体の壁時計は最長 shard で決まるため、
重い Testcontainers IT が偶然同じ shard へ集中すると、他の shard が早く終わっても全体は縮まらない。
貪欲法による重み付け振り分けで、この偏りをほぼ解消できる。実測重み表での試算（各プロファイルを
独立に均等化した旧XML重みの試算であり、新しい実時間重みでの壁時計短縮を保証しない）:

| プロファイル | 安定ハッシュ相当の偏り（最大/最小比） | 貪欲法適用後 |
|---|---|---|
| migration込み（backend-nightly-full.yml） | 1.32倍 | 1.00倍 |
| migration除外（通常PR・大多数の経路） | 1.85倍 | 1.00倍 |

### 全 shard の実時間の採取

通常 CI と nightly full は `scripts/run-ci-tests-with-observability.sh` を共用し、全 shard（現在 8）の
クラス開始/終了・elapsed・task終端を専用 runtime artifact（保持7日）へ記録する。
重みの一次入力は通常 `:test` の class suite 開始/終了間の実時間（ミリ秒）であり、JUnit XML の
`testsuite.time` や MFA内部のサービス計測値とは同一視しない。outer suite がある場合は子を
二重加算せず、nested-only は独立した最外 suite を合算する。全 shard の
正常終端、既存 coverage guard、実行集合内の重複0を確認し、未実行キーを固定ソース・filter・
タグ・既存skip等で分類する（全重みキーとの単純一致を完全性判定にしない）。

未実行・timeout・terminal欠損は `completed=false` / `testStepExitCode=null` として扱う。
Gradleのreturn値だけでなく実際のstep outcomeも記録し、EXIT trapの0だけで成功と判定しない。
既存のFROM-CACHE/UP-TO-DATEはGradleのexit 0を維持するが、freshな採取完了とは扱わない。
採取欠損をgreenや高速化の証拠にせず、各 workflow の採択済み cap（PR Check は step 95/110 分・job 110/120 分。nightly は step 105・job 120、deploy は step 85/105・job 100/120）・全テスト集合・必須集約gateを維持する（shard 数は 2026-11-18 に 6 から 8 へ変更した）。
MFAの固定prefix数値ログとMFA/billingのcase窓のみ追加採取し、テスト出力全量や秘密値は収録しない。

### 重み表の再生成タイミング

- テストクラス数が大幅に増減した（数十クラス規模の追加/削除）とき。
- 特定 shard が恒常的に他より大きく偏るようになった（重み表が古くなり、新規/削除されたテストの
  実行時間を反映できていない）とき。
- 数クラス程度の日常的な増減では、既定重み（中央値）が自動的に吸収するため都度の再生成は不要。
  未掲載数が番人の閾値（100）を超える前に再生成する。

再生成手順: `cd backend && ./scripts/regen-shard-weights.sh [RUN_ID[,RUN_ID...]] [nightly-full]`（`gh` CLI 必須。既定で
**backend-nightly-full.yml** の直近成功 run の小さい runtime artifact から再構築する。
`RUN_ID` は**カンマ区切りで複数**渡せ、クラスごとに「観測できた run」だけで平均する（ある run で観測されなかったクラスを
0 秒として分母に入れない。観測 run が全 run より少ないクラスは重み表ヘッダーに `observed=k/n` を残す）。
全 run を検証し終えてから 1 回だけ原子的に置換し、1 本でも失敗すれば既存の重み表は変わらない（メッセージに失敗した run ID を含む）。
shard 数 N は run の job 名・step 名（`Run tests (shard n / N`）から導出し、job 数・step の N・artifact の index 集合 `0..N-1` が
一致しなければ拒否する（6 shard の run も 8 shard の run も受け付ける）。run ID と既存の第2引数 `nightly-full|backend-ci` を明示できるが、現在 `backend-ci` はfull profileを
メタデータから確認できないため拒否する）。migration/Flyway 実測非0は明白な除外入力を拒否する
補助チェックであり、full profileの証明とはせず、固定workflow/sourceの実行内容を別証拠で確認する。同一run/head/attemptの全 shard、fresh・exit 0・正常task終端、
class開始/終了の対応と重複0、artifact digest・安全pathを検証し、不正・欠損時は出力を変えない。
検証後のみ同一ディレクトリの一時ファイルから原子的に置換し、XMLへfallbackしない。
historical run と現在HEADの一致は要求せず、現在sourceの未計測・削除・arch/perf/skip分類と
適用判断は別manifestで検分する。生成後は上記の既存4profile coverage guardを実行する。
集計対象の回帰検証: `cd backend && python3 -m unittest scripts/test_regen_shard_weights.py`（`ShardTotalDerivationTest` / `MultiRunAverageTest` / `CiShardWorkflowStaticTest` を含む）。割り当て・番人の単体テスト: `cd backend && ./gradlew -p build-logic test`。
生成後は `verifyShardCoverage`（番人込み）が通ることを確認する。来歴（全 run の id / head / attempt）は重み表ヘッダーに残る。

### コンテキスト構成ごとの並べ替えと起動計測（CMP-261111-0508 甲）

1 shard・1 JVM に積むテストクラスの Spring コンテキスト構成は十数〜数十種あり、`spring.test.context.cache.maxSize=10` を超えると
LRU で追い出されて同じ構成が再ロードされる。これを減らすため、**同じ構成のクラスを連続して実行する** Orderer と、
**実際のロード回数・所要を測る** Listener を `:test` だけに入れている。割り当て（shard の振り分け）・重み表・上限値・fork 数は変えない。

- **`ContextGroupClassOrderer`**（`backend/src/test/java/com/mannschaft/app/common/testing/`）: JUnit Jupiter の `ClassOrderer`。
  各クラスの `MergedContextConfiguration`（MCC）を `BootstrapUtils` / `TestContextBootstrapper.buildMergedContextConfiguration` で
  **コンテキストを起動せず・静的初期化も起こさず**に構築する。キーは MCC の構成要素（testClass を除く）を正規化した文字列の SHA-256 先頭12桁
  （同一性ハッシュ・testClass 名を含めない。toString が同一性ハッシュしか持たない customizer はクラス名で代替する）。
  同キーのクラスを連続させ、グループ順は最小 FQCN 昇順・グループ内は FQCN 昇順（入力順に依存しない）。
  Spring 非使用のクラスと MCC 構築が例外になったクラスは単独グループで、実行は継続する。
  並べ替えの所要は `[ci-ctx-orderer] classes=N fallback=F ms=M` で標準出力に出る（目安: 1 shard あたり 30 秒以内。`fallback` は 0 が正常）。
- **`[ci-ctx]` の計測**（`ContextLoadObservingCustomizer` と `ContextKeyTestExecutionListener`。どちらも `backend/src/test/resources/META-INF/spring.factories` で登録）:
  `[ci-ctx] jvm=<pid> seq=<JVM内通し番号> class=<FQCN> key=<12桁> event=<load|hit|reload|error> loadMs=<n> cacheSize=<n>` を出す。
  `load`=その JVM で初めてのロード、`reload`=その JVM で既にロード済みのキーの再ロード（`@DirtiesContext`・LRU 追い出し・手動破棄の後）、
  `hit`=キャッシュヒット（クラスにつき1回）。**load / reload はリスナーの順序から推定せず、実際のロード地点で観測する**:
  公式の差し替え口 `ContextCustomizerFactory` の customizer が、ローダーの構築中（refresh の直前）に開始時刻を取り、
  `ContextRefreshedEvent` で確定する。ロードを起こしたクラス（`mcc.getTestClass()`）の行が、呼び出し元（ServletTestExecutionListener・
  DI リスナー・PER_CLASS / PER_METHOD・`@DirtiesContext` の各モード）に関わらず出る。`loadMs` は refresh 直前から完了まで（切り上げ・生成と Bean 定義登録の手前は含まない）。
  `cacheSize` は `ContextClosedEvent` まで数えた本計測の生存コンテキスト数で、ContextCache の内部（非公開フィールド）は読まない。
  hit は `ContextKeyTestExecutionListener`（`getOrder()`=2100・DI リスナーの後）が、クラスの最初の `beforeTestMethod` で、そのクラスの load/reload が
  無くコンテキストが生きていれば出す。Spring 6.2 の `BootstrapUtils` は `CacheAwareContextLoaderDelegate` の差し替え用 SpringProperties キーを持たない
  （クラス名が固定）ため、委譲クラスの継承ではなく customizer を使う（全インスタンスを等価にしてあり、MCC＝キャッシュキーを割らない）。
  計測自体の失敗は握って `event=error`（`error=<例外クラス名>`）を出し、テストは落とさない。本来のコンテキスト起動失敗はテスト失敗のまま。
  プロパティ値などの秘密値は出さない（キー・クラス名・数値・例外クラス名だけ）。
- **有効範囲**: build-logic の `ContextOrdererSettings.systemProperties(taskName, -PcontextOrderer)` が **`:test` だけ**に
  `junit.jupiter.testclass.order.default`（Orderer）と `mannschaft.ci.ctx.log=true`（`[ci-ctx]` の標準出力）を渡す。
  `archTest`・`archUnitTest`・`archUnitFreezeStoreIntegrityTest`・`perfTest`・ER 図タスクには何も渡さない
  （Listener は spring.factories 経由で全 Spring テストに載るが、system property が無いタスクでは何も出力しない）。
- **無効化**: `-PcontextOrderer=false` で **Orderer だけ**を外せる（`[ci-ctx]` の計測は常に有効）。効果測定の対照 run に使う。
- **収集**: `backend/scripts/run-ci-tests-with-observability.sh` の init スクリプトが `:test` の `[ci-ctx]`・`[ci-ctx-orderer]` 行を
  runtime-diag artifact の `context-progress.log` に集める。`regen-shard-weights.sh` の ZIP 許可リストには `context-progress.log` を加えてあるが、
  **REQUIRED ではなく、重み計算にも使わない**（有無で重み表は変わらない）。
- 検査: `ContextGroupClassOrdererTest`・`ContextKeyTestExecutionListenerTest`（BE。PER_CLASS・MOCK Web・BEFORE/AFTER_EACH_TEST_METHOD・@BeforeEach の遅延と失敗の検体を含む）、`ContextOrdererSettingsTest`（build-logic）、
  `ContextProgressLogCompatibilityTest`（`scripts/test_regen_shard_weights.py`）。

### 構成キーの棚卸しと認可 override の番人（CMP-261111-0508 追撃①）

構成キー（MCC）の種類を減らす施策（第一陣 A: 基底 `AbstractMySqlIntegrationTest` への `@AutoConfigureMockMvc`、第二陣 C2: 外部境界だけを mock する中間基底）の前提として、
全テストクラスの「Spring が実際に適用する BeanOverride」と構成キーを棚卸しし、凍結スナップショットと照合する番人を置いている。

- **棚卸し**（`ContextOverrideCensus`。`backend/src/test/java/com/mannschaft/app/common/testing/`）: テスト出力ディレクトリの全 .class を静的初期化なしで読み、
  JUnit のテストクラス（具象・テストメソッドを継承込みで持つ・@Nested を含む）ごとに `BootstrapUtils` で MCC を構築し（コンテキストは起動しない）、
  その `ContextCustomizer` のうち BeanOverride 系 customizer（`BeanOverrideContextCustomizer` の handlers。Boot の旧 `@MockBean` 系も読む）から実効 override を読む。
  `BeanOverrideHandler.forTestClass` は外側クラス（@Nested）とクラス上の宣言を探索しないので使わない。読み方を知らない BeanOverride 系 customizer は解析失敗にする。
  分類は **非 Spring・override 0 件（正常な結果）・override あり・解析失敗** の4つで、別々に集計する（`[ctx-census] classes=… nonSpring=… zero=… overrides=… failed=… distinctKeys=… authzOverrides=…`）。
- **番人**（`ContextOverrideCensusGuardTest`）: 凍結スナップショット `backend/src/test/resources/context-override-census/snapshot.tsv` と照合し、次を red にする。
  - **認可型 override の追加**（クラスごと。新規クラスは空集合と比べる）。認可型は denylist（`AccessGuard`・`AccessControlService`・`AuthTokenService`・`UserLocaleCache`・
    `ScopeConcealingAccessGate`・`UserRoleRepository`・接尾辞 `*Guard`/`*Authorizer`/`*PermissionEvaluator`）と、明示リスト（`GoogleCalendarWebhookService` など、認可判定を持つ型。
    `ContextOverrideCensus.AUTHZ_EXPLICIT`）の両方。**認可型 override の追加は殿の判断事項**であり、スナップショットの再生成で通してはならない。
  - 認可型ではない override（外部アダプタの mock など）の増減は **照合しない**（red にならず、再生成も要らない）。スナップショットには認可型 override だけを載せる。
    理由: 目的は認可 Bean の mock 化を防ぐことで、無関係な `@MockitoBean` の変更に全 PR へ再生成を強いるのは摩擦が大きい。
  - **既知の対象の解析失敗・新規の未分類（スナップショットに無い解析失敗）・対象クラスの脱落**（削除・改名・Spring 非使用化）。
  - **distinct key 数のラチェット**: 実測が `!distinctKeysBaseline` を超えたら red。構成キーを増やす変更は共通構成へ寄せる。第一陣 A の後は基準値−1 以下になる想定で、
    減ったら再生成して基準値を下げる（下げ忘れても red にはしないが、次の増加を見逃さないよう同じ PR で下げる）。
- **スナップショットの生成・再生成手順**（初期値は 2026-11-20 の main の実測から生成した）:
  再生成が要るのは、認可型 override の削除（基準値を下げる）・クラスの削除や改名・解析失敗の解消・distinct key 数の減少のときに限る。
  1. `cd backend && ./scripts/gradle-turnstile.sh ./gradlew test --tests com.mannschaft.app.common.testing.ContextOverrideCensusGuardTest` を実行する（red でもよい）。
     番人は毎回、実測を `backend/build/context-override-census/snapshot.tsv` に書き出す（`distinctKeysBaseline`・`authzOverrideBaseline` は実測値）。
  2. 差分を確かめてから `cp build/context-override-census/snapshot.tsv src/test/resources/context-override-census/snapshot.tsv` で写し、同じ PR でコミットする。
     **認可型 override の行が増えていないこと**と、増えた構成キーの理由を PR 本文に書く。手で編集しない。
  3. もう一度 1. を実行し green を確かめる。
- **基底の MockMvc 自動構成（第一陣 A）**: `AbstractMySqlIntegrationTest` は `@AutoConfigureMockMvc`（addFilters は既定の true）を持つ。
  MockMvc を使う派生と使わない派生で MCC が分かれ、構成キー（起動する context 数）が倍加していたのを 1 つに揃えるため。派生クラスは `@AutoConfigureMockMvc` を再宣言しない。
  **`addFilters = false` を宣言した派生は従来どおり別キー**（意図した分離。認可フィルタを通さない試験）で、数を増やさない目的でも勝手に外さない・足さない。
  フィルタを通す側（true）は未認証の 401 など「フィルタでしか起きない拒否」が実効になる。実効値は `MockMvcAddFiltersEffectiveValueTest` が全派生で宣言どおりかを検査する。
  A の後の基準値は distinct key 338（A の前は 347）、認可型 override は 1124 件で不変。
- **番人自体の CI 時間**: `ContextOverrideCensusGuardTest`（実測 約 280 秒。全テストクラスの MCC を構築する）と `MockMvcAddFiltersEffectiveValueTest`（約 30 秒）は
  全クラスを走査するため重い。shard-weights.properties に実測に近い重みを登録し、LPT に計算させている（登録が無いと中央値の重みで割り付き、特定 shard の Run tests が静かに伸びる）。
  走査結果の JVM 内共有は採らない: forkEvery=180・8 shard で 2 つのクラスが同じ JVM・同じ shard に入る保証が無く、効果が偶然になるため。
  重み表の再生成（regen-shard-weights.sh）は実測から上書きするので、以後は自動で追随する。
- **関連する試練**（CMP-261111-0508 追撃①。A の前は red のものを含む）: `MockMvcAddFiltersEffectiveValueTest`（AC-2(a) addFilters の実効値。直接・中間基底・合成注釈・@Nested の false 宣言は false のまま、
  それ以外の派生は true）、`MockMvcFilterAppliedIT` / `MockMvcFilterNotAppliedIT`（AC-2(b) フィルタでしか起きない応答: 本文なしの 401・X-Frame-Options）、
  `MockMvcContextUnificationTest` / `MockMvcContextUnificationIT`（AC-2(c) 旧「基底のみ」と旧「MockMvc=true」の MCC 統合・context 共有・MockMvc と builder の構築）、
  `ContextKeyTestExecutionListenerTest` の AC-5（共有キーの起動失敗は両クラスの失敗として残り、`[ci-ctx]` の error にしない）、
  build-logic の `CiTestRuntimeConfigPinTest`（AC-4: 既定 maxSize 10・CI の `-Pspring.test.context.cache.maxSize=5`・forkEvery 180・maxParallelForks 2・shard 8・timeout は PR Check の test 95/110・job 110/120、deploy の test 85/105・job 100/120、nightly の test 105・job 120 を変えない（条件付きの値は通常/migration ありの順））。
