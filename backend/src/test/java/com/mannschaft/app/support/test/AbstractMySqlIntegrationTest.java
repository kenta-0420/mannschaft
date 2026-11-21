package com.mannschaft.app.support.test;

import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * MySQL Testcontainers を共有する統合テストの基底クラス。
 *
 * <p><b>背景</b>: {@code @SpringBootTest} を別ファイルで増やすと、テストクラスごとに
 * 別の {@link org.springframework.test.context.MergedContextConfiguration} が生成され、
 * Spring TestContext Cache に新しい ApplicationContext エントリが追加される。
 * その結果、CI のテスト JVM ヒープが圧迫され OOM 連鎖が発生する（実際に F03.15 Phase 1 PR で発生）。</p>
 *
 * <p><b>方針</b>: 内部Service/TX試験とMockMvc結合試験は本基底クラスのMOCK設定を継承し、
 * 構成を完全に揃える（同じ {@code @ActiveProfiles}、同じ {@code @MockitoBean}、
 * 同じ {@code @DynamicPropertySource}）ことで、TestContext Cache に登録される
 * ApplicationContext を 1 つだけにする。これにより:</p>
 * <ul>
 *   <li>MySQL Testcontainer も 1 個だけ起動される（singleton container パターン）</li>
 *   <li>Bean 群も 1 セットだけメモリに保持される</li>
 *   <li>テスト全体の実行時間も短縮される</li>
 * </ul>
 *
 * <p><b>使い方</b>:</p>
 * <pre>{@code
 * @DisplayName("XXX 統合テスト")
 * @EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
 * class XxxIntegrationTest extends AbstractMySqlIntegrationTest {
 *
 *     @Autowired
 *     private XxxRepository repository;
 *
 *     @Test
 *     void someTest() { ... }
 * }
 * }</pre>
 *
 * <p><b>注意</b>:</p>
 * <ul>
 *   <li>内部Service/TX・MockMvcの派生クラスでは {@code @SpringBootTest} / {@code @Testcontainers} /
 *       {@code @ActiveProfiles} / {@code @DynamicPropertySource} /
 *       {@code @MockitoBean(StringRedisTemplate)} を再宣言しないこと。
 *       再宣言すると ApplicationContext 構成が分岐し、TestContext Cache が分裂して
 *       本来の目的（OOM 防止）が損なわれる。</li>
 *   <li>実HTTPクライアント・WebSocket/STOMPの接続契約を検査する場合だけ、
 *       {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} でWebEnvironmentを上書きする。
 *       必要性を試験のJavadocに記し、{@code @DirtiesContext(AFTER_CLASS)} で専用contextを閉じる。
 *       MySQL・ActiveProfiles・外部依存など他の共通設定は継承する。正本: TEST_CONVENTION.md §3.1.2。</li>
 *   <li>{@code @EnabledIf} は <b>例外として派生クラスでも再宣言が必須</b>。
 *       JUnit 5 の {@code @EnabledIf} は {@code @Inherited} メタアノテーションを持たないため、
 *       基底クラスに付与しただけでは派生クラスのテスト実行可否判定に効かない
 *       （Docker 未起動環境でテストが skip されず、{@code DockerClientProviderStrategy} が例外を投げる）。</li>
 * </ul>
 */
// 構成キーの統合（CMP-261111-0508 追撃①）: MockMvc を使う派生（@AutoConfigureMockMvc）と使わない派生で
// MergedContextConfiguration が分かれ、Spring TestContext Cache のエントリ（=起動する context 数）が倍加していた。
// 基底で MockMvc を自動構成して両者を同じキーに揃える。addFilters は既定（true）のまま:
// セキュリティフィルタを実効にする側へ寄せることで、フィルタを通す試験の意味を変えない。
// addFilters=false を宣言する派生は従来どおり別キーになる（意図した分離。変更しない）。
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
public abstract class AbstractMySqlIntegrationTest {

    /**
     * 全統合テストで共有する MySQL コンテナ。
     *
     * <p>JUnit Jupiter の {@code @Container} は付けず、JVM ライフサイクルで起動・停止する
     * （Testcontainers 公式の "singleton container" パターン）。
     * 静的初期化ブロックで {@link MySQLContainer#start()} を 1 度だけ呼ぶ。</p>
     */
    @SuppressWarnings("resource")
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(false)
            // 専用Testcontainerで実SQLのtriggerを作成可能にし、既存Flyway試験と同じ条件に揃える。
            .withCommand("--log-bin-trust-function-creators=1")
            // WSL2 環境でMySQL初期化がVHDのI/O待ちで2分超になりTestcontainersのタイムアウトを超える問題を回避するため、
            // /var/lib/mysql をtmpfs(RAM)に置くことで初期化を数秒に短縮する。
            .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"));

    static {
        if (isDockerAvailable()) {
            MYSQL.start();
            grantProcessToAppUser();
        }
    }

    /**
     * アプリ接続ユーザーに PROCESS 権限を与える。TRIGGER 内で実行中の文は {@code information_schema.PROCESSLIST} 上で
     * DEFINER（root）のスレッドとして表示されるため、権限が無いと「処理中」の窓を観測できない
     * （F06.4.1 の冪等 receipt 試験が同時実行中の状態を PROCESSLIST で検出する）。専用 Testcontainer 内だけの権限。
     */
    private static void grantProcessToAppUser() {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("GRANT PROCESS ON *.* TO '" + MYSQL.getUsername() + "'@'%'");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("テスト用 MySQL への PROCESS 権限付与に失敗しました", e);
        }
    }

    /** Redis は外部依存のため Mock 化（全派生テストで共通に必要）。 */
    @MockitoBean
    protected org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    /**
     * Mock 化した {@code StringRedisTemplate} は既定で {@code opsForValue()} が null を返す。本番コードの Valkey 呼び出しが
     * NPE になり、障害時フォールバック（DataAccessException の捕捉）と区別できなくなるため、実物と同じく
     * {@code ValueOperations} を返す既定を共通設定として置く（get は null＝キー無し）。個別の試験は上書きしてよい。
     */
    @org.junit.jupiter.api.BeforeEach
    void stubRedisTemplateValueOperations() {
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> ops =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(redisTemplate.opsForValue()).thenReturn(ops);
    }

    /**
     * 共有コンテキストの users.locale キャッシュ（Bean が無い縮小構成では null）。
     *
     * <p>CMP-261119-1855: 本基底クラスは単一の MySQL を共有し、テスト環境は {@code ddl-auto=create} のため、
     * 別構成のコンテキストが起動するたびにスキーマが作り直され users の id が 1 から再採番される。
     * 共有コンテキストの {@code UserLocaleCache}（TTL 5 分）は userId をキーに前テストの値を保持し続けるため、
     * 再利用された id の新ユーザーに前テストの locale（ja）が返り、早期拒否応答などが
     * 英語にならない順序依存の失敗になった。各テストの前に破棄して持ち越しを断つ。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.mannschaft.app.common.i18n.UserLocaleCache sharedUserLocaleCache;

    @org.junit.jupiter.api.BeforeEach
    protected void clearSharedUserLocaleCache() {
        if (sharedUserLocaleCache != null) {
            sharedUserLocaleCache.clear();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        FakeOAuthProviders.registerProperties(registry);
    }

    /**
     * Docker が利用可能かを判定する。CI ランナー以外（開発者ローカルで Docker 未起動など）
     * では Testcontainers を起動できないため、本メソッドが {@code false} を返すと
     * {@code @EnabledIf} により全テストがスキップされる。
     */
    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }
}
