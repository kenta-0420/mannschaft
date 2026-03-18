# テスト規約 (TEST_CONVENTION.md)

本ドキュメントはプロジェクト全体のテスト方針を定義する。
各技術スタックの基本設定（ツール選定・カバレッジ目標・テストデータ作成パターン等）は既存の規約に記載済みのため、本ドキュメントでは**テストの分類・設計方針・CI/CD統合**に焦点を当てる。

### 関連ドキュメント（既存の記載箇所）
| 内容 | 参照先 |
|------|--------|
| JUnit 5 / Mockito / JaCoCo 80% / テスト容易性 | `BACKEND_CODING_CONVENTION.md` §4 |
| TestFixture 方式（テストデータ作成パターン） | `BACKEND_CODING_CONVENTION.md` テストデータ作成パターン |
| テスト実行環境（Testcontainers / CI / テスト分離） | `BACKEND_CODING_CONVENTION.md` テスト実行環境 |
| Vitest / Vue Test Utils / テスト対象優先順位 / 配置ルール | `FRONTEND_CODING_CONVENTION.md` §11 |
| 新モジュール追加時のテスト要件 | `.claudecode.md` §7 |
| pre-commit フック（Checkstyle / SpotBugs / ESLint） | `BACKEND_CODING_CONVENTION.md` pre-commit フック |

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

---

## 3. 結合テスト設計方針

### 3.1 アノテーションの使い分け

| アノテーション | 用途 | 起動範囲 |
|--------------|------|---------|
| **`@WebMvcTest(XxxController.class)`** | Controller 層のみテスト（Service はモック） | Controller + Security Filter のみ |
| **`@SpringBootTest` + `@AutoConfigureMockMvc`** | Controller → Service → Repository の一気通貫テスト | アプリケーション全体 |

- **原則として `@SpringBootTest` + `@AutoConfigureMockMvc` を使用する**。Service をモックする `@WebMvcTest` は、Controller 層に固有のロジック（リクエストマッピング、バリデーション等）を個別に検証したい場合のみ使用する
- **理由**: 本プロジェクトの Controller は薄い設計（§.claudecode.md 原則4）であり、Service をモックしても検証価値が低い。実 DB を含めた一気通貫テストのほうが信頼性が高い

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

### 3.3 Redis を使うテスト

Redis に依存するテスト（JWT ブラックリスト、レートリミット等）では Testcontainers の Redis コンテナを使用する。

```java
public abstract class AbstractIntegrationTestWithRedis extends AbstractIntegrationTest {

    // 親クラスと同様に Singleton Container パターンを採用
    static final GenericContainer<?> REDIS;

    static {
        REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);
        REDIS.start();
    }

    @DynamicPropertySource
    static void configureRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }
}
```

- Redis を使わないテストは `AbstractIntegrationTest` を継承する（Redis コンテナの起動コストを避ける）
- Redis を使うテストのみ `AbstractIntegrationTestWithRedis` を継承する

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
│   ├── AbstractIntegrationTestWithRedis.java # 結合テスト基底（MySQL + Redis）
│   └── TestFixture.java                      # 共通テストデータ
└── [feature]/
    ├── [Feature]ServiceTest.java             # 単体テスト
    ├── [Feature]ControllerIntegrationTest.java  # 結合テスト
    └── [Feature]TestFixture.java             # 機能固有テストデータ
```

- 配置は `BACKEND_CODING_CONVENTION.md` テストデータ作成パターンのルールに従う
- 単体テストと結合テストは同一パッケージに配置し、クラス名のサフィックスで区別する

### 6.2 フロントエンド

`FRONTEND_CODING_CONVENTION.md` §11 の配置ルールに従う（対象ファイルと同一ディレクトリに `.spec.ts` を配置）。

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

`BACKEND_CODING_CONVENTION.md` CI/CD パイプライン規約をベースに、テスト実行を中心とした詳細フローを定義する。

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

- `FRONTEND_CODING_CONVENTION.md` §11 の方針に従い、Phase 11 で着手する
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

## 10. テストに関する禁止事項

| 禁止事項 | 理由 |
|---------|------|
| `Thread.sleep()` をテスト内で使用する | 非同期処理の待機には `Awaitility` を使用する |
| テスト間の実行順序に依存する | `@TestMethodOrder` での順序制御は禁止。各テストは独立して実行可能であること |
| 本番 DB や外部 API に直接接続するテスト | Testcontainers またはモックを使用する |
| テスト専用の `if (isTest)` 分岐をプロダクションコードに入れる | DI やプロファイルで切り替える |
| `@Disabled` を理由なく放置する | 一時的な無効化は許容するが、理由をコメントに記載し、1スプリント以内に解決する |
| 手書きの INSERT SQL でテストデータを作成する | TestFixture 経由で作成する（`BACKEND_CODING_CONVENTION.md` テストデータ作成パターン参照） |
