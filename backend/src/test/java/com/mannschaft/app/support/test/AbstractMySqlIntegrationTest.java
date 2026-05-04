package com.mannschaft.app.support.test;

import org.junit.jupiter.api.condition.EnabledIf;
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
 * <p><b>方針</b>: 全 {@code @SpringBootTest} を本基底クラスから派生させ、
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
 *   <li>派生クラスでは {@code @SpringBootTest} / {@code @Testcontainers} /
 *       {@code @ActiveProfiles} / {@code @DynamicPropertySource} /
 *       {@code @MockitoBean(StringRedisTemplate)} を再宣言しないこと。
 *       再宣言すると ApplicationContext 構成が分岐し、TestContext Cache が分裂して
 *       本来の目的（OOM 防止）が損なわれる。</li>
 *   <li>{@code @EnabledIf} は <b>例外として派生クラスでも再宣言が必須</b>。
 *       JUnit 5 の {@code @EnabledIf} は {@code @Inherited} メタアノテーションを持たないため、
 *       基底クラスに付与しただけでは派生クラスのテスト実行可否判定に効かない
 *       （Docker 未起動環境でテストが skip されず、{@code DockerClientProviderStrategy} が例外を投げる）。</li>
 * </ul>
 */
@SpringBootTest
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
            .withReuse(false);

    static {
        if (isDockerAvailable()) {
            MYSQL.start();
        }
    }

    /** Redis は外部依存のため Mock 化（全派生テストで共通に必要）。 */
    @MockitoBean
    protected org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
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
