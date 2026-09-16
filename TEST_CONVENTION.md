# テスト規約 (TEST_CONVENTION.md)

本ドキュメントはプロジェクト全体のテスト方針を定義する。
各技術スタックの基本設定（ツール選定・カバレッジ目標・テストデータ作成パターン等）は既存の規約に記載済みのため、本ドキュメントでは**テストの分類・設計方針・CI/CD統合**に焦点を当てる。

### 関連ドキュメント（既存の記載箇所）
| 内容 | 参照先 |
|------|--------|
| JUnit 5 / Mockito / JaCoCo 80% / テスト容易性 | `backend/BACKEND_CODING_CONVENTION.md` §4 |
| TestFixture 方式（テストデータ作成パターン） | `backend/BACKEND_CODING_CONVENTION.md` テストデータ作成パターン |
| テスト実行環境（Testcontainers / CI / テスト分離） | `backend/BACKEND_CODING_CONVENTION.md` テスト実行環境 |
| Vitest / Vue Test Utils / テスト対象優先順位 / 配置ルール | `frontend/FRONTEND_CODING_CONVENTION.md` §11 |
| 新モジュール追加時のテスト要件 | `backend/.claudecode.md` §7 |
| pre-commit フック（Checkstyle / SpotBugs / ESLint） | `backend/BACKEND_CODING_CONVENTION.md` pre-commit フック |

---

## 1. テスト分類と責務

### 1.1 バックエンド

| 分類 | 対象 | ツール | DB | スコープ |
|------|------|--------|-----|---------|
| **単体テスト** | Service のビジネスロジック | JUnit 5 + Mockito | 不使用（モック） | クラス単位 |
| **結合テスト** | Controller → Service → Repository の一気通貫 | JUnit 5 + Testcontainers (MySQL 8.0) + MockMvc | 実DB | 機能（feature）単位 |
| **E2E テスト** | 複数機能を跨ぐシナリオ | `@SpringBootTest` + `TestRestTemplate` | 実DB | ユーザーシナリオ単位 |

### 1.2 フロントエンド

| 分類 | 対象 | ツール |
|------|------|--------|
| **単体テスト** | Composables / Zodスキーマ / Piniaストア | Vitest + Vue Test Utils |
| **コンポーネントテスト** | 重要なUIコンポーネント（フォーム送信・条件分岐表示等） | Vitest + Vue Test Utils |
| **E2E テスト** | ブラウザ上の主要ユーザーシナリオ | Playwright（Phase 11 で整備） |

### 1.3 テスト比率の目安（テストピラミッド）

```
        /  E2E  \          少数（主要シナリオのみ）
       /  結合   \         各APIエンドポイントに最低1本
      /  単体     \        Service のビジネスロジックを網羅
     ‾‾‾‾‾‾‾‾‾‾‾‾‾‾
```

- **単体テスト**: 最多。高速でフィードバックが早いため、ビジネスロジックの検証はここに集中させる
- **結合テスト**: 各 API エンドポイントの正常系 + 主要な異常系（認証・認可・バリデーション）
- **E2E テスト**: 主要なユーザーシナリオ（会員登録→ログイン→チーム作成等）のみ。数を絞る

---

## 2. 単体テスト設計方針

### 2.1 モック戦略

| レイヤー | モック対象 | 理由 |
|---------|-----------|------|
| **Service テスト** | Repository, 他機能の Service, DomainEventPublisher, 外部クライアント | DB・外部依存を排除し、ビジネスロジックだけを検証する |
| **Repository テスト** | 原則テストしない（結合テストでカバー） | Spring Data JPA の派生クエリは信頼してよい。カスタムクエリ（QueryDSL / `@Query`）のみ結合テストで検証 |
| **Controller テスト** | 原則テストしない（結合テストでカバー） | 薄い Controller を単体でテストする価値は低い |
| **Mapper テスト** | 原則テストしない | MapStruct 自動生成コードを手動検証する意味は薄い。複雑な `default` メソッドを含む場合のみ単体テストを書く |

### 2.2 テストすべき観点

1. **正常系**: メインの処理フロー
2. **境界値**: 最大・最小・ゼロ・空文字・上限ちょうど等
3. **異常系**: `BusinessException` がスローされるケース全パターン
4. **権限分岐**: ロールによって挙動が変わる処理

### 2.3 テスト不要な箇所

- 単純な Getter / Setter（Lombok 自動生成）
- MapStruct 自動生成コード
- Entity の `@PrePersist` / `@PreUpdate`（結合テストで自然にカバーされる）
- 設定クラス（`@Configuration`）の Bean 登録（起動テストでカバー）

### 2.4 時刻依存テスト（date-pin）の禁則と正攻法

実時刻（`Instant.now()` / `LocalDate.now()` / `LocalDateTime.now()` 等）に依存するテストは、
**固定した過去日付（例: `Instant.parse("2026-06-01T...")`）を実時刻と直接比較しない**こと。
固定日付が「今日」を跨いだ瞬間に `isBefore` / `isAfter` / 期限判定の真偽が反転し、テストが
将来日付で flaky に壊れる（実例: `JobQrTokenServiceTest` の date-pin → `Clock` 注入で根治）。

**安全な書き方（いずれか）:**

1. **本番コードに `Clock` を注入する** — 時刻を参照する Service は `Clock` を DI し、テストでは
   `Clock.fixed(NOW, ZoneOffset.UTC)` を渡す。`now()` は `Instant.now(clock)` 等 `Clock` 経由にする。
   （例: `JobCheckInService` / `JobQrTokenService` は注入済み）
2. **入力・期待値の両方を相対化する** — 固定基準を使わず `now().plusDays(7)` / `now().minusMonths(1)`
   のように「現在からの相対」で組み、アサートも相対値で行う。両辺が同じ時刻軸で動くため日跨ぎで壊れない。
   > ⚠️ **「両辺が同じ時刻軸」は前提であって保証ではない。** テスト側と被テスト側が**別のタイムゾーンで
   > 暦日を算出**していると、相対化していても日跨ぎで壊れる。とくにフロントエンドで実害が出ている
   > （§2.4.1）。相対化を選ぶ前に、被テスト側が明示 TZ 変換をしていないかを必ず確認すること。
3. **固定入力に対する相対アサート** — メソッドへ固定日時を **明示的に引数で渡し**、その固定入力に対する
   出力を固定値でアサートする（実時刻を参照しないため安全。例: `SlaPolicy.calcDueAt(base)`）。

**禁則パターン（書いてはいけない）:**

```java
// NG: 固定の過去日付を実時刻と比較 — 日跨ぎで判定が反転して壊れる
assertThat(entity.getDeadline()).isAfter(LocalDateTime.now());        // deadline が固定2026なら将来falseに
assertThat(LocalDate.now()).isBefore(LocalDate.of(2026, 12, 31));     // 2026/12/31を過ぎたら壊れる
```

> 監査記録（2026-06-13 / Phase3a B-4）: `backend/src/test` 配下の固定 2026 リテラルと `now()` を併用する
> 約70ファイルを精査した結果、「固定過去日付 vs 実時刻」の破壊パターンは **0 件**（上記 1〜3 の安全形のみ）。
> 既知の `JobQrTokenServiceTest` は `Clock` 注入済みのため対象外。本節は再発防止の規約として明文化したもの。

### 2.4.1 フロントエンド（Vitest）— TZ 軸の食い違いによる暦日ずれ **【必須】**

**テストで期待値を組み立てるとき、引数なしの `dayjs()` / `new Date()` を使ってはならない。**

前節の「相対化すれば安全」は **テスト側と被テスト側が同じ時刻軸で暦日を出している場合にのみ**成り立つ。
本プロジェクトのコンポーネントは `useDatetime` の `userTimezone`（既定 `Asia/Tokyo`）で
`dayjs(...).tz(...)` と**明示変換**するものが多い。一方テストで素の `dayjs()` を使うと
**実行プロセスの TZ** で評価される。**CI は `TZ=UTC` で走る**ため、両者の暦日は
**UTC 15:00〜24:00（= JST の翌日 00:00〜09:00）の窓で 1 日ずれる**。

```ts
// NG: プロセス TZ で暦日を出している。コンポーネントが .tz('Asia/Tokyo') なら CI の特定時間帯で壊れる
const targetDate = dayjs().add(2, 'day').toDate()
const targetDateIso = dayjs(targetDate).format('YYYY-MM-DD')
```

**正攻法 — 時計を固定する。相対化では解決しない:**

```ts
beforeAll(() => {
  // UTC 03:00 = JST 12:00。どちらの TZ で評価しても同じ暦日になる「安全な昼間」を選ぶ。
  // 境界近く（UTC 15:00〜24:00 など）を選ぶと固定しても暦日がずれるため意味がない。
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-08-11T03:00:00Z'))
})
afterAll(() => {
  vi.useRealTimers()   // 他スペックへの汚染を防ぐため必ず戻す
})
```

- `toFake: ['Date']` で**日付系だけ**を差し替える。タイマー全体を止めると `flushPromises` や
  Vue の非同期描画と干渉することがある。
- 固定する瞬間は**両 TZ で同じ暦日になる時刻**を選ぶ。固定しさえすればよいのではない。

> 実例（2026-08-12 / PR #2744）: `ScheduleExceptionPanel.spec.ts` が
> `dayjs().add(2, 'day')`（プロセス TZ）で期待値を組む一方、コンポーネントの `formatDate()` は
> `dayjs(date).tz('Asia/Tokyo')` で変換していた。CI が UTC 15:15 に走った際に暦日が 1 日ずれて
> 3 件が落ち、**FE を触る全 PR を数時間ブロック**した。日付直書きを避けて相対化した結果、
> 「その日が来たら壊れる」爆弾が「特定の時間帯に走ると壊れる」爆弾に化けた形である。
> **相対化は解ではない。時計を止めるのが解。**

---

### 2.5 ログ出力を検証するテスト（`ListAppender`）はロガーの実効レベルを自ら設定・復元すること **【必須】**

`ch.qos.logback.core.read.ListAppender` を対象クラスの `Logger` に付けてログ出力内容を検証する
プレーンな単体テスト（Spring コンテキストを起動しないもの）は、`@BeforeEach` で**対象ロガーの
実効レベルを明示的に設定**し、`@AfterEach` で**元のレベルへ確実に復元**すること（取得した
`getLevel()` の戻り値をそのまま戻す。null なら null に戻し、継承状態へ戻す）。

**理由**: `backend/build.gradle.kts` の `setForkEvery` により、同一 gradle テストフォーク内で複数
のテストクラスが JVM を共有する。先に `@ActiveProfiles("test")` の `@SpringBootTest`（`test`
プロファイルは `logback-spring.xml` で root レベルを WARN に設定）が走ると、その状態が同一フォーク
内の後続のプレーン単体テストへ持ち越され、`log.info` 等が実効レベル未達で握りつぶされて
`ListAppender` に何も届かない。**ローカルで対象クラス単体だけを実行すると Spring コンテキストが
起動しないため再現せず、CI 特有の実行順依存ですり抜ける。**

---

## 3. 結合テスト設計方針

### 3.1 アノテーションの使い分け

| アノテーション | 用途 | 起動範囲 |
|--------------|------|---------|
| **`@WebMvcTest(XxxController.class)`** | Controller 層のみテスト（Service はモック） | Controller + Security Filter のみ |
| **`@SpringBootTest` + `@AutoConfigureMockMvc`** | Controller → Service → Repository の一気通貫テスト | アプリケーション全体 |

- **原則として `@SpringBootTest` + `@AutoConfigureMockMvc` を使用する**。Service をモックする `@WebMvcTest` は、Controller 層に固有のロジック（リクエストマッピング、バリデーション等）を個別に検証したい場合のみ使用する
- **理由**: 本プロジェクトの Controller は薄い設計（§.claudecode.md 原則4）であり、Service をモックしても検証価値が低い。実 DB を含めた一気通貫テストのほうが信頼性が高い

### 3.1.1 Controller テストは MockMvc 経由必須（Bean 直呼び禁止）**【必須】**

**Controller のテストは必ず MockMvc で HTTP リクエストを発行して検証すること。**
**Controller を `@Autowired` して、そのメソッドを Java から直接呼ぶ流儀を禁止する。**

```java
// ❌ 禁止 — HTTP 層を迂回する「偽の統合テスト」
@Autowired
private VillageMeetupController controller;

@Test
void listMeetups() {
    ApiResponse<List<MeetupResponse>> res = controller.list(villageId, PLANNING, 0, 20);
    assertThat(res.getData()).hasSize(1);
}

// ✅ 必須 — HTTP を通す
mockMvc.perform(get("/api/v1/villages/{villageId}/meetups", villageId)
                .param("status", "PLANNING"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].status").value("PLANNING"));
```

#### 理由

Controller Bean の直呼びは、**Java の型検査が通る範囲しか検証しない**。以下は Controller が担う契約でありながら、直呼びでは **一切検証されない**:

| 検証されない項目 | 具体例 |
|---|---|
| **URL パス** | `@RequestMapping` のパスが FE の叩く URL と一致しているか |
| **HTTP メソッド** | `PUT` か `POST` か（FE が POST を送り BE は PUT のみ、等） |
| **`@RequestParam` の enum バインド** | `?status=OPEN` が `VillageMeetupStatus` に束縛できず 400 になる |
| **リクエスト JSON の形状** | `List<LocalDate>` に object 配列を送ると 400 になる |
| **レスポンス JSON のエンベロープ形状** | `{items, page, size, total}` か `Page` の `{content, totalElements}` か |
| **Bean Validation** | `@Valid` は HTTP 経由でしか発火しない（直呼びは素通り） |
| **例外 → HTTP ステータス変換** | `GlobalExceptionHandler` を経由しないため 403/404/409 の別が出ない |

**この規約は実害から生まれた。** 2026-07-15 の実機精査で、村ドメインに FE/BE の契約不一致が **17 件** 確定した。その分布は Controller テストの流儀と完全に一致していた:

- `*ControllerIntegrationTest`（Bean 直呼び。Calendar / Monsho / MatchRecruit / Festival / Representative）→ パス・メソッド・形状バグを **素通し**
- **Controller テスト皆無**（寄合 / Meetup。`VillageMeetupServiceTest` のみ）→ 判明分だけで **6 件破損**（投票パス・集計パス・status enum・voteType・作成リクエスト・レスポンス形状）
- `*ControllerTest`（MockMvc。参加申請 / 村作成申請）→ **BE は正しく pin 済**。FE のみ逸脱していた

つまり **Bean 直呼びのテストが緑であること自体が、契約が守られている証拠にならない**。むしろ「テストがある」という誤った安心を与える点で、テストが無いより有害である。

#### 導入コスト

MockMvc は本プロジェクトで既に **196 ファイル・3531 箇所** で使われている確立した多数派パターンであり、**新規導入コストはゼロ**である。金型は `VillageJoinRequestControllerTest` を参照すること。

#### 既存テストの扱い

- **新規 Controller テストは MockMvc 必須**（本規約の適用対象）
- **既存の Bean 直呼びテストは順次移行する**。一斉書き換えは他ドメインへの影響調査が必要なため行わない
- 既存の `*ControllerIntegrationTest` に MockMvc 版を**追加**する場合、既存側は挙動の回帰検知として**残置してよい**（置き換えではなく補完）

#### characterization test（現契約の固定）について

既に正しく実装済みの Controller に後追いで MockMvc テストを足す場合、それは **red → green の red テストではなく、現契約を固定する characterization test（回帰防止柵）**である。**初回実行から green になるのが正常**であり、「試練が red にならない」ことは異常ではない。この性質はテストクラスの Javadoc と PR 説明に明記し、検分官が誤判定しないようにすること。

### 3.2 結合テスト基底クラス

Testcontainers の設定を毎回書く冗長さを排除するため、基底クラスを用意する。
**Singleton Container パターン**を採用し、全テストクラスでコンテナを共有する（クラスごとの起動・破棄を防ぎ、テスト実行を高速化する）。

```
src/test/java/com/mannschaft/app/common/
└── AbstractIntegrationTest.java
```

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional  // 各テスト後に自動ロールバック
public abstract class AbstractIntegrationTest {

    // Singleton Container: 全テストクラスで1つのコンテナを共有する
    // @Testcontainers / @Container は使わない（クラスごとの再起動を防止）
    static final MySQLContainer<?> MYSQL;

    static {
        MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_test")
            .withUsername("test")
            .withPassword("test");
        MYSQL.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        // Spring Docker Compose Support を無効化（BACKEND_CODING_CONVENTION.md §5 参照）
        registry.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    // 認証済みリクエストのヘルパー
    protected String generateTestToken(Long userId, RoleType role) {
        // テスト用 JWT を生成して返す
    }
}
```

> **なぜ Singleton Container か**: `@Container` + `@Testcontainers` はテストクラスごとにコンテナを起動・破棄する。テストクラスが増えると CI が著しく遅くなるため、`static` ブロックで1回だけ起動し、JVM 終了時に Testcontainers の Ryuk が自動停止する方式を採用する。

#### 使い方
```java
class AuthControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void ログイン成功時にJWTが返される() throws Exception {
        // given
        userRepository.save(TestFixture.defaultUser());

        // when & then
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new LoginRequest("test@example.com", "Password1!"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").exists());
    }
}
```

### 3.3 Valkey を使うテスト

Valkey に依存するテスト（JWT ブラックリスト、レートリミット等）では Testcontainers の Valkey コンテナを使用する。

```java
public abstract class AbstractIntegrationTestWithValkey extends AbstractIntegrationTest {

    // 親クラスと同様に Singleton Container パターンを採用
    static final GenericContainer<?> VALKEY;

    static {
        VALKEY = new GenericContainer<>("valkey/valkey:8-alpine")
            .withExposedPorts(6379);
        VALKEY.start();
    }

    @DynamicPropertySource
    static void configureValkey(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", VALKEY::getFirstMappedPort);
    }
}
```

- Valkey を使わないテストは `AbstractIntegrationTest` を継承する（Valkey コンテナの起動コストを避ける）
- Valkey を使うテストのみ `AbstractIntegrationTestWithValkey` を継承する

---

## 4. テスト命名規則

### 4.1 バックエンド（JUnit 5）

テストメソッド名は**日本語を使用する**。テストの意図を明確に伝えることを最優先とする。

#### パターン

```java
// 基本パターン: 操作_条件_期待結果
@Test
void チーム作成_ADMIN権限で正常なリクエスト_201が返される() { }

@Test
void チーム作成_MEMBER権限_403が返される() { }

@Test
void チーム作成_チーム名が空_バリデーションエラー() { }

// 条件が不要な場合は省略可
@Test
void ログイン成功時にJWTが返される() { }
```

#### テストクラスの命名
| テスト種類 | クラス名パターン | 例 |
|-----------|----------------|-----|
| 単体テスト | `[Feature]ServiceTest` | `AuthServiceTest` |
| 結合テスト | `[Feature]ControllerIntegrationTest` | `AuthControllerIntegrationTest` |
| E2E テスト | `[Scenario]E2ETest` | `UserRegistrationE2ETest` |

### 4.2 フロントエンド（Vitest）

`FRONTEND_CODING_CONVENTION.md` §11 の配置ルール（対象ファイルと同一ディレクトリに `.spec.ts`）に従う。

```typescript
// describe: 対象の名前、it: 日本語で振る舞いを記述
describe('useAuth', () => {
  it('ログイン成功時にトークンが保存される', async () => { })
  it('401エラー時にストアがクリアされる', async () => { })
})
```

---

## 5. テストの AAA パターン

全てのテストメソッドは **Arrange → Act → Assert**（Given → When → Then）の構造で記述する。

```java
@Test
void チーム作成_ADMIN権限で正常なリクエスト_201が返される() throws Exception {
    // given: テストデータの準備
    var user = userRepository.save(TestFixture.userWithRole(RoleType.ADMIN));
    var request = new CreateTeamRequest("新チーム", "説明");

    // when: テスト対象の実行
    var result = mockMvc.perform(post("/api/v1/teams")
        .header("Authorization", "Bearer " + generateTestToken(user.getId(), RoleType.ADMIN))
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(request)));

    // then: 結果の検証
    result.andExpect(status().isCreated())
          .andExpect(jsonPath("$.data.name").value("新チーム"));
}
```

- `// given`, `// when`, `// then` のコメントは省略可。ただし、3つのセクションが視覚的に区別できるよう空行を挟むこと
- 1つのテストメソッドで検証する Assert は**1つの振る舞い**に絞る。ステータスコードとレスポンスボディの両方を検証するのは OK（同一振る舞いの異なる側面）。異なる条件のテストを1メソッドに詰め込むのは NG

---

## 6. テスト配置ルール

### 6.1 バックエンド

```
src/test/java/com/mannschaft/app/
├── common/
│   ├── AbstractIntegrationTest.java          # 結合テスト基底（MySQL）
│   ├── AbstractIntegrationTestWithValkey.java # 結合テスト基底（MySQL + Valkey）
│   └── TestFixture.java                      # 共通テストデータ
└── [feature]/
    ├── [Feature]ServiceTest.java             # 単体テスト
    ├── [Feature]ControllerIntegrationTest.java  # 結合テスト
    └── [Feature]TestFixture.java             # 機能固有テストデータ
```

- 配置は `backend/BACKEND_CODING_CONVENTION.md` テストデータ作成パターンのルールに従う
- 単体テストと結合テストは同一パッケージに配置し、クラス名のサフィックスで区別する

### 6.2 フロントエンド

`frontend/FRONTEND_CODING_CONVENTION.md` §11 の配置ルールに従う（対象ファイルと同一ディレクトリに `.spec.ts` を配置）。

---

## 7. Gradle タスク定義

単体テストと結合テストを分離実行できるようにする。

```kotlin
// build.gradle.kts

// 単体テスト（デフォルト）
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// 結合テスト
tasks.register<Test>("integrationTest") {
    description = "Runs integration tests with Testcontainers"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
}

// 全テスト実行
tasks.register<Test>("allTests") {
    description = "Runs all tests (unit + integration)"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
}
```

結合テストクラスには `@Tag("integration")` を付与する:

```java
@Tag("integration")
class AuthControllerIntegrationTest extends AbstractIntegrationTest {
    // ...
}
```

---

## 8. CI/CD パイプライン

### 8.1 基本方針

`backend/BACKEND_CODING_CONVENTION.md` CI/CD パイプライン規約をベースに、テスト実行を中心とした詳細フローを定義する。

### 8.2 GitHub Actions ワークフロー（バックエンド）

```yaml
# .github/workflows/backend-ci.yml
name: Backend CI

on:
  pull_request:
    branches: [main]
    paths:
      - 'src/**'
      - 'build.gradle.kts'
      - 'settings.gradle.kts'
      - 'gradle/**'
      - 'gradlew'
      - 'gradlew.bat'
  push:
    branches: [main]
    paths:
      - 'src/**'
      - 'build.gradle.kts'
      - 'settings.gradle.kts'
      - 'gradle/**'
      - 'gradlew'
      - 'gradlew.bat'

jobs:
  build-and-test:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Cache Gradle packages
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle.kts') }}
          restore-keys: ${{ runner.os }}-gradle-

      - name: Build
        run: ./gradlew build -x test

      - name: Checkstyle
        run: ./gradlew checkstyleMain

      - name: SpotBugs
        run: ./gradlew spotbugsMain

      - name: Unit Tests
        run: ./gradlew test

      - name: Integration Tests
        run: ./gradlew integrationTest

      - name: JaCoCo Coverage Report
        # allTests の結果を集計するため、単体+結合の両方を含むレポートを生成する
        # build.gradle.kts で jacocoTestReport.executionData に integrationTest を追加すること（§8.6 参照）
        run: ./gradlew jacocoTestReport

      - name: Check Coverage Threshold
        run: ./gradlew jacocoTestCoverageVerification

      - name: Upload Coverage Report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: jacoco-report
          path: build/reports/jacoco/test/html/
```

### 8.3 GitHub Actions ワークフロー（フロントエンド）

フロントエンドは**別リポジトリ**で管理するため、このワークフローはフロントエンドリポジトリの `.github/workflows/` に配置する。

```yaml
# .github/workflows/frontend-ci.yml（フロントエンドリポジトリに配置）
name: Frontend CI

on:
  pull_request:
    branches: [main]
  push:
    branches: [main]

jobs:
  lint-and-test:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'

      - name: Install dependencies
        run: npm ci

      - name: Lint (ESLint)
        run: npx eslint .

      - name: Format Check (Prettier)
        run: npx prettier --check .

      - name: Type Check
        run: npx nuxi typecheck

      - name: Unit Tests
        run: npx vitest run --coverage
```

### 8.4 パイプライン実行フロー

Backend CI と Frontend CI は**別リポジトリ・別ワークフロー**のため独立して実行される。
各ワークフロー内のステップは上から順に逐次実行される。

```
[バックエンドリポジトリ]          [フロントエンドリポジトリ]
PR 作成 / main push              PR 作成 / main push
    │                                │
    ▼                                ▼
Backend CI（逐次実行）           Frontend CI（逐次実行）
 1. Build（コンパイル確認）        1. ESLint + Prettier
 2. Checkstyle（スタイル）         2. Type Check
 3. SpotBugs（静的バグ検出）       3. Vitest（単体テスト）
 4. Unit Tests（単体テスト）           │
 5. Integration Tests（結合）          ▼
 6. JaCoCo（80% 未満で失敗）       全 Pass + Approve → マージ可能
    │
    ▼
全 Pass + Approve → マージ可能
```

### 8.5 JaCoCo カバレッジ集計設定

`jacocoTestReport` はデフォルトでは `test` タスクの実行結果（`.exec` ファイル）のみを集計する。結合テストのカバレッジも含めるため、以下を `build.gradle.kts` に追加する:

```kotlin
// build.gradle.kts
tasks.jacocoTestReport {
    // 単体テスト + 結合テストの両方の実行データを集計する
    executionData.setFrom(fileTree(layout.buildDirectory) {
        include("jacoco/test.exec", "jacoco/integrationTest.exec")
    })
    dependsOn(tasks.test, tasks.named("integrationTest"))

    reports {
        html.required.set(true)
        xml.required.set(true)  // CI でのカバレッジ可視化ツール連携用
    }
}

tasks.jacocoTestCoverageVerification {
    executionData.setFrom(fileTree(layout.buildDirectory) {
        include("jacoco/test.exec", "jacoco/integrationTest.exec")
    })
    dependsOn(tasks.test, tasks.named("integrationTest"))

    violationRules {
        rule {
            limit {
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}
```

### 8.6 マージ条件（ブランチ保護ルール）

GitHub のブランチ保護ルールで以下を強制する:

| 設定 | 値 |
|------|-----|
| Require status checks to pass | `build-and-test`（Backend）, `lint-and-test`（Frontend） |
| Require approvals | 1名以上 |
| Dismiss stale reviews | ON（新しい push で既存の Approve を取り消す） |
| Require branches to be up to date | ON（main の最新を取り込んでからマージ） |

---

## 9. E2E テスト方針（Phase 11 で整備）

### 9.1 バックエンド E2E

- `@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)` + `TestRestTemplate` を使用
- 複数機能を跨ぐシナリオを検証する（例: 会員登録 → ログイン → チーム作成 → メンバー招待）
- `@Transactional` は使わない（実際のコミットを含めた動作を確認するため）
- テスト後のクリーンアップは `@AfterEach` で **テーブル TRUNCATE** を実行する:

```java
@AfterEach
void cleanup() {
    // 外部キー制約を一時無効化してから全テーブルを TRUNCATE する
    // E2E テスト基底クラスに共通メソッドとして実装する
    jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
    for (String table : ALL_TABLES) {
        jdbcTemplate.execute("TRUNCATE TABLE " + table);
    }
    jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
}
```

- `ALL_TABLES` リストは Flyway のマイグレーションと同期して管理する。新テーブル追加時はリストにも追加すること

### 9.2 フロントエンド E2E（Playwright）

- `frontend/FRONTEND_CODING_CONVENTION.md` §11 の方針に従い、Phase 11 で着手する
- テスト対象は主要なユーザーシナリオに絞る:
    - 会員登録 → メール確認 → ログイン
    - チーム作成 → メンバー招待 → 権限変更
    - 投稿作成 → 編集 → 削除
    - 決済フロー（Stripe テストモード）
- ヘッドレスモードで CI 上で実行する

### 9.3 E2E テストの配置

```
src/test/java/com/mannschaft/app/e2e/     # バックエンド E2E
frontend/e2e/                              # フロントエンド E2E（Playwright）
```

---

## 9.5 ContentVisibilityChecker のテスト規約

機能側 Service / Controller のテストで `ContentVisibilityChecker` を `@MockBean`
または `@Mock` する場合、必ず以下のユーティリティ経由でスタブすること:

- `com.mannschaft.app.common.visibility.testsupport.VisibilityCheckerTestSupport`

直接 `when(checker.canView(...))` を書くと、本基盤の API 進化（例: 多テナント
対応 §17.Q14）で全テストが一斉に壊れる。ユーティリティ経由なら API 変更時に
本ユーティリティ 1 箇所の修正で全機能テストが追従する。

直接 `when()` を書く場合は、テストクラスのコメントに理由を明記すること。

### 利用例

```java
@MockBean
private ContentVisibilityChecker checker;

@BeforeEach
void setUp() {
    VisibilityCheckerTestSupport.allowAll(checker);
}
```

### 提供される 5 メソッド

| メソッド | 用途 |
|---------|------|
| `allowAll(checker)` | 全 type / 全 contentId / 全 userId に対して allow（最も多いユースケース） |
| `denyAll(checker)` | 全 deny。`assertCanView` は例外スロー |
| `allowFor(checker, type, ids)` | 特定 `ReferenceType` の特定 contentId 集合のみ allow |
| `allowForUser(checker, userId)` | 特定 userId のみ allow（他は deny） |
| `denyWithReason(checker, reason)` | `decide()` でカスタム `DenyReason` を返す |

詳細は設計書 `docs/features/F00_content_visibility_resolver.md` §13.8 を参照。

---

## 9.6 バッチ（`@Scheduled`）を書くときの規約 — 番人あり

本番は複数 Pod で動く。**`@Scheduled` を書いたメソッドは、何もしなければ Pod 数だけ同時に走る**
（＝二重通知・二重課金・二重集計）。従来この規約は `ShedLockConfig` の Javadoc 一覧という
「人間の善意」だけで維持されていたが、番人 `ScheduledBatchGuardTest` が CI で機械的に強制する。

| ルール | 内容 |
|---|---|
| 1 | `@Scheduled` には **`@SchedulerLock` を必ず併記**する。例外は `@PodLocalScheduled` のみ |
| 2 | `@SchedulerLock` には **`lockAtMostFor` を必ず明示**する（既定 30m への暗黙依存を禁止） |
| 3 | `@Scheduled` には **`@BatchEndpoint` を必ず併記**する。例外は `@BatchEndpointExempt` のみ |
| 4 | 短周期バッチ（起動間隔 1 時間以下）は **`lockAtMostFor` を起動間隔より長く**する（同値も不可） |

```java
/** 予約リマインドを送出する（毎分）。 */
@BatchEndpoint(name = "reservation-reminder-dispatch", description = "予約リマインド送出")
@Scheduled(cron = "0 * * * * *")
@SchedulerLock(name = "reservationReminderDispatchBatch", lockAtMostFor = "PT5M")
public void dispatch() { ... }
```

**`lockAtMostFor` を必ず書かせる理由**: 未指定だと `@EnableSchedulerLock(defaultLockAtMostFor = "30m")`
の既定値に暗黙依存する。既定 30 分は数秒で終わるワーカーには長すぎ（Pod が異常終了するとロックが
30 分残り、その間バッチが完全停止する）、1 時間かかる夜間集計には短すぎる（処理中に他 Pod が
二重起動しうる）。**そのバッチの最大実行時間を書き手に必ず考えさせる**のが本ルールの目的である。

**`lockAtMostFor` が起動間隔以下だと何が起きるか（ルール 4）**: 1 回の実行が `lockAtMostFor` を
超えた時点でロックが失効するため、次の起動が前の実行と重なる。**同値が最も危険**で、実行が
わずかに超過しただけで重なる。番人は起動間隔を `cron` / `fixedRate` / `fixedDelay`（文字列版含む）
から算出し、cron は Spring の `CronExpression` に発火時刻を列挙させて**最小**間隔を採る。
**算出できない場合は安全側に倒して落とす**（`${prop}` を既定値なしで書いて番人を迂回させないため。
`${prop:0 0 3 * * *}` のように既定値付きで書くこと）。
日次・週次・月次は次の起動まで 24 時間以上あり重なりが起きないため**対象外**であり、
これらは間隔ではなく最悪ケースの処理時間から `lockAtMostFor` を決める。

**例外マーカーの使い方**: `@PodLocalScheduled` / `@BatchEndpointExempt` は番人の出力を黙らせる力を
持つため、**理由の記述（`value()` の文字列リテラル）と対象メソッドの Javadoc を必須**とする。
これを二次番人 `BatchMarkerAnnotationGuardTest` が機械的に検証する（免除リストは無い）。
「ロックの付け方が分からない」は付与理由にならない。付与が正当なのは、
**ロックを掛けるとかえって壊れる**場合（Pod ローカルのメモリバッファ flush・Pod ごとの死活監視）と、
**数秒間隔の高頻度ワーカーで実行履歴が有害**な場合だけである。

**番人自体のテスト（`@Repeatable` の罠）**: `@Scheduled` は `@Repeatable(Schedules.class)` であり、
1 メソッドに 2 つ以上書くと javac は `@Scheduled` を直接付けず `@Schedules` コンテナに包む。
`areAnnotatedWith(Scheduled.class)` だけを見る番人は**複数スケジュール指定のバッチを丸ごと取り逃す**。
メタテスト `ScheduledBatchGuardConditionTest` がこのケースを fixture で実証している。
番人の判定ロジックを触るときは、必ずメタテストの負例で「違反が返ること」を確認すること
（**違反 0 件は番人が動いていることの証明にはならない**）。

---

## 9.7 `@Transactional` なテストで通知の配送結果を検証しない — 番人あり

**`@Transactional` が効いたテストは業務トランザクションをコミットしない。**
よって `@TransactionalEventListener(AFTER_COMMIT)` で配送される通知は 1 件も作られない。
その状態で通知の配送結果を検証すると、テストは**何を壊しても必ず通る**（偽の緑）。

- 「1 件以上であること」を検証していれば移設した瞬間に赤くなるので気づける。
- **「0 件であること」を検証していると永遠に緑のままで、CI では検出できない。**
- クラスに注釈が無くても、**基底クラスからの継承**や `@DataJpaTest` で実効的にトランザクショナルになる。

実例（#3140）: L8（PR #3135）で `ScheduleKeepConvertContractIT` が実際にこの形だった。
`@Transactional` なクラスの中で、外側 TX から見えない通知行を素の `DataSource` から新接続で数え、
`assertThat(countConvertedNotifications(supporterId)).isZero()` と検証していた。
隣のテストが赤くなったから気づけただけで、単独なら永遠に緑だった。

**正しい形は 2 つある。テストが守りたい契約で選ぶ。**

| 形 | 使う場面 | 金型 |
|---|---|---|
| `@Transactional` を外し `TransactionTemplate` で明示コミット | 配送まで含めて検証したい（本戦役の正規形） | `ScheduleCommentNotificationPartialFailureIT` / `ScheduleNotificationTransactionBoundaryIT` |
| `@RecordApplicationEvents` で配送イベントの publish を検証 | 業務 TX 内で観測できる契約までで割り切る（配送内容はリスナー側の単体テストが持つ） | `ScheduleKeepConvertContractIT` AC-15b（PR #3135） |

番人 `TransactionalTestNotificationObservationGuardTest` が CI で機械的に拒否する。
適法な例外は同クラスの `ALLOWED` に理由付きで列挙し、
検体テスト側で「その例外が実際に判定へ引っかかること（＝効いていない例外を残さないこと）」まで検証する。
通知を `AFTER_COMMIT` へ移設する手順は `backend/.claudecode.md` §6.1 を参照。

---

## 10. テストに関する禁止事項

| 禁止事項 | 理由 |
|---------|------|
| `Thread.sleep()` をテスト内で使用する | 非同期処理の待機には `Awaitility` を使用する |
| テスト間の実行順序に依存する | `@TestMethodOrder` での順序制御は禁止。各テストは独立して実行可能であること |
| 本番 DB や外部 API に直接接続するテスト | Testcontainers またはモックを使用する |
| テスト専用の `if (isTest)` 分岐をプロダクションコードに入れる | DI やプロファイルで切り替える |
| `@Disabled` を理由なく放置する | 一時的な無効化は許容するが、理由をコメントに記載し、1スプリント以内に解決する |
| 手書きの INSERT SQL でテストデータを作成する | TestFixture 経由で作成する（`backend/BACKEND_CODING_CONVENTION.md` テストデータ作成パターン参照） |
| **Controller を `@Autowired` して直接メソッド呼び出しでテストする** | HTTP 層を迂回し、URL パス・HTTP メソッド・enum バインド・JSON 形状・`@Valid`・例外→ステータス変換を一切検証できない。村ドメインで契約不一致 17 件を素通しにした実害あり。MockMvc を使うこと（**§3.1.1** に詳細）|
