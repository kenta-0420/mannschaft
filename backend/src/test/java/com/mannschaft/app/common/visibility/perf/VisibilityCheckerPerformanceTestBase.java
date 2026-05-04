package com.mannschaft.app.common.visibility.perf;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * F00 共通可視性判定 — 性能テスト基底クラス。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §13.4 完全一致。
 *
 * <p>各 Resolver / ファサードの SQL 発行回数シナリオを Testcontainers の MySQL 8.0 で
 * 実測する基盤。H2 では Hibernate Statistics や IN 句バッチの挙動が異なるため、
 * 本基盤では実 MySQL を必須とする。
 *
 * <p><strong>使い方</strong>: 本クラスを継承し、{@code @Autowired} で
 * {@code ContentVisibilityChecker} や対象 Resolver を注入してシナリオを記述する。
 * 各テストの先頭は {@link SqlIntentCounter#totalCount()} 等で発行 SQL 数を検証する。
 *
 * <pre>{@code
 * class FooVisibilityPerformanceTest extends VisibilityCheckerPerformanceTestBase {
 *     @Autowired private ContentVisibilityChecker checker;
 *
 *     @Test
 *     void filter_homogeneous_scopes_uses_at_most_3_sql() {
 *         checker.filterAccessible(ReferenceType.BLOG_POST, ids, userId);
 *         assertThat(SqlIntentCounter.totalCount()).isLessThanOrEqualTo(4);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>StringRedisTemplate モック</strong>: 性能テストは Redis 接続不要のため、
 * {@link MockitoBean} で置き換える。MembershipQueryCache などのキャッシュ層が
 * Redis を参照する場合に NPE を防ぐ。
 *
 * <p><strong>StatementInspector</strong>: {@code application-test.yml} で
 * {@link SqlIntentCounter} を Hibernate に登録済み。{@link #resetSqlCounter()} で
 * 各テスト開始時に捕捉リストをクリアする。
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
public abstract class VisibilityCheckerPerformanceTestBase {

    /** Testcontainers MySQL 8.0 コンテナ。Spring Boot 起動より先に立ち上がる. */
    @Container
    @SuppressWarnings("resource")
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("mannschaft_test")
        .withUsername("test")
        .withPassword("test");

    /**
     * Spring Boot のデータソース URL を Testcontainers の動的 URL に差し替える。
     *
     * @param registry Spring の動的プロパティレジストリ
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    /** 性能テスト中に Redis を呼ばないようモックで差し替える. */
    @MockitoBean
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    /** 各テスト開始時に SQL 捕捉リストをクリアする. */
    @BeforeEach
    void resetSqlCounter() {
        SqlIntentCounter.reset();
    }
}
