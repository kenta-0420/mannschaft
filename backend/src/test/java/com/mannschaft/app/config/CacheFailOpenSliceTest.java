package com.mannschaft.app.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Valkey(Redis) 断を<b>例外注入で再現</b>し、キャッシュ操作が業務処理を巻き添えにしないこと
 * （fail-open）を Spring AOP プロキシ越しに検証するスライステスト。
 *
 * <h3>何を守るテストか</h3>
 *
 * <p>本番で起きていた事故は「Valkey が落ちると {@code @CacheEvict} を持つミューテーション
 * （{@code RoleService.changeRole} 等）が 500 になり、<b>降格・除名ができなくなる</b>」というもの。
 * 御裁可方針は「Redis が落ちている間も権限の変更は成功させる」であり、本テストはその契約を
 * 機械的に固定する番人である。</p>
 *
 * <h3>テスト構成</h3>
 *
 * <p>Docker / Testcontainers / 実 Redis は使わない。{@link CacheManager#getCache} が
 * 「get / put / evict / clear のすべてで {@link RedisConnectionFailureException} を投げる
 * Mockito モック {@link Cache}」を返すスタブを {@link AnnotationConfigApplicationContext} に載せ、
 * <b>本番の配線クラスである {@link CacheErrorHandlingConfig} をそのまま同居させる</b>
 * （＝ハンドラの登録経路そのものを検証対象に含める）。
 * 金型は {@code IncidentBannerServiceCacheTest} の最小キャッシュコンテキスト。</p>
 */
@DisplayName("キャッシュ基盤障害時の fail-open（例外注入スライス）")
class CacheFailOpenSliceTest {

    private static final String CACHE_NAME = "role-permissions";

    /**
     * 全キャッシュ操作が Valkey 断を模して例外を投げる最小コンテキスト構成。
     *
     * <p>{@link CacheErrorHandlingConfig} は本テストの
     * {@link AnnotationConfigApplicationContext} 生成時に併せて登録する（本番と同じ配線経路）。</p>
     */
    @Configuration
    @EnableCaching
    static class FailingCacheSliceConfig {

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        Cache failingCache() {
            Cache cache = mock(Cache.class);
            when(cache.getName()).thenReturn(CACHE_NAME);
            when(cache.get(any())).thenThrow(new RedisConnectionFailureException("Valkey 断（注入）"));
            doThrow(new RedisConnectionFailureException("Valkey 断（注入）")).when(cache).put(any(), any());
            doThrow(new RedisConnectionFailureException("Valkey 断（注入）")).when(cache).evict(any());
            doThrow(new RedisConnectionFailureException("Valkey 断（注入）")).when(cache).clear();
            return cache;
        }

        @Bean
        CacheManager cacheManager(Cache failingCache) {
            CacheManager cacheManager = mock(CacheManager.class);
            when(cacheManager.getCache(any())).thenReturn(failingCache);
            when(cacheManager.getCacheNames()).thenReturn(List.of(CACHE_NAME));
            return cacheManager;
        }

        @Bean
        SampleCachedService sampleCachedService() {
            return new SampleCachedService();
        }
    }

    /**
     * {@code RoleService} 相当の被験クラス（読み取り＝{@code @Cacheable} / 権限変更＝{@code @CacheEvict}）。
     *
     * <p>実行回数はフィールド直読みではなく {@link #bodyExecutions()} 経由で取得すること。
     * CGLIB プロキシはコンストラクタを走らせないため、プロキシ側のフィールドは初期化されておらず
     * {@code service.field} の直接参照は NPE になる（メソッド呼び出しのみがターゲットへ委譲される）。</p>
     */
    public static class SampleCachedService {

        /** メソッド本体（DB 相当）の実行回数。 */
        private final AtomicInteger bodyExecutionCount = new AtomicInteger();

        /** メソッド本体の実行回数を返す（プロキシ経由で安全に読める）。 */
        public int bodyExecutions() {
            return bodyExecutionCount.get();
        }

        @Cacheable(value = CACHE_NAME, key = "#userId")
        public String findPermissions(long userId) {
            bodyExecutionCount.incrementAndGet();
            return "permissions-of-" + userId;
        }

        /** {@code RoleService.changeRole} 相当。Valkey 断でも成功しなければならない。 */
        @CacheEvict(value = CACHE_NAME, key = "#userId")
        public String changeRole(long userId, String newRole) {
            bodyExecutionCount.incrementAndGet();
            return userId + ":" + newRole;
        }

        /** {@code allEntries = true}（clear 経路）。 */
        @CacheEvict(value = CACHE_NAME, allEntries = true)
        public String removeAllMembers() {
            bodyExecutionCount.incrementAndGet();
            return "removed";
        }

        /** put 経路（キャッシュ書き込みのみ失敗しても戻り値は正しくなければならない）。 */
        @CachePut(value = CACHE_NAME, key = "#userId")
        public String refreshPermissions(long userId) {
            bodyExecutionCount.incrementAndGet();
            return "refreshed-" + userId;
        }
    }

    private AnnotationConfigApplicationContext ctx;
    private SampleCachedService service;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        ctx = new AnnotationConfigApplicationContext(
                FailingCacheSliceConfig.class, CacheErrorHandlingConfig.class);
        service = ctx.getBean(SampleCachedService.class);
        registry = ctx.getBean(SimpleMeterRegistry.class);
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    private double failOpenCount(String operation) {
        return registry.get(LoggingCacheErrorHandler.FAIL_OPEN_METRIC)
                .tag("operation", operation)
                .counter()
                .count();
    }

    @Test
    @DisplayName("配線確認: CacheInterceptor に LoggingCacheErrorHandler が実際に注入されている")
    void CacheInterceptorにハンドラが配線される() {
        CacheInterceptor interceptor = ctx.getBean(CacheInterceptor.class);

        assertThat(interceptor.getErrorHandler()).isInstanceOf(LoggingCacheErrorHandler.class);
    }

    @Test
    @DisplayName("changeRole_Valkey断_evictが失敗しても例外を投げず正常完了する（降格・除名を止めない）")
    void CacheEvict付きミューテーションはValkey断でも成功する() {
        assertThatCode(() -> {
            String result = service.changeRole(42L, "MEMBER");
            assertThat(result).isEqualTo("42:MEMBER");
        }).doesNotThrowAnyException();

        assertThat(service.bodyExecutions()).isEqualTo(1);
        assertThat(failOpenCount("evict")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("removeAllMembers_Valkey断_allEntries=trueのclear失敗でも例外を投げず正常完了する")
    void allEntriesのCacheEvictもValkey断で成功する() {
        assertThatCode(() -> assertThat(service.removeAllMembers()).isEqualTo("removed"))
                .doesNotThrowAnyException();

        assertThat(failOpenCount("clear")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("findPermissions_Valkey断_例外を投げずメソッド本体（DB相当）が実行され正しい値を返す")
    void CacheableはValkey断でもメソッド本体が実行される() {
        String first = service.findPermissions(7L);
        String second = service.findPermissions(7L);

        assertThat(first).isEqualTo("permissions-of-7");
        assertThat(second).isEqualTo("permissions-of-7");
        // get が常に失敗＝常にキャッシュミス扱い。本体は毎回実行される（結果は常に正しい）。
        assertThat(service.bodyExecutions()).isEqualTo(2);
        assertThat(failOpenCount("get")).isEqualTo(2.0);
        // ミス後の put も失敗するが、これも握り潰される
        assertThat(failOpenCount("put")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("refreshPermissions_Valkey断_CachePutのput失敗でも戻り値は正しい")
    void CachePutはValkey断でも戻り値が正しい() {
        String result = service.refreshPermissions(9L);

        assertThat(result).isEqualTo("refreshed-9");
        assertThat(failOpenCount("put")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("fail-openカウンタにキャッシュ名タグが付き、どのキャッシュが壊れたか特定できる")
    void failopenカウンタにキャッシュ名タグが付く() {
        service.changeRole(1L, "ADMIN");

        assertThat(registry.get(LoggingCacheErrorHandler.FAIL_OPEN_METRIC)
                .tag("operation", "evict")
                .tag("cache", CACHE_NAME)
                .counter()
                .count())
                .isEqualTo(1.0);
    }
}
