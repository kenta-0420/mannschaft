package com.mannschaft.app.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CacheConfig} のスモークテスト。
 *
 * <p>F10.5 Phase 10-α §5.1.4: Caffeine.recordStats() が確実に有効化されており、
 * 個別 Cache を組み立てた際にヒット/ミス統計が記録できることを担保する。
 * 本テストは Spring コンテキスト起動を伴わない単体テストで意図的に軽量化している。</p>
 */
@DisplayName("CacheConfig (F10.5 Phase 10-α Caffeine recordStats)")
class CacheStatsTest {

    @Test
    @DisplayName("caffeineConfig() の builder で組み立てた Cache は hit/miss 統計を記録する")
    void caffeine_records_stats() {
        CacheConfig config = new CacheConfig();
        Caffeine<Object, Object> builder = config.caffeineConfig();

        // 共通ビルダーから個別 Cache を組み立てる
        Cache<String, String> cache = builder.build();

        // 1 件 put → 同じキーで 2 回 hit、別キーで 1 回 miss
        cache.put("k1", "v1");
        assertThat(cache.getIfPresent("k1")).isEqualTo("v1");
        assertThat(cache.getIfPresent("k1")).isEqualTo("v1");
        assertThat(cache.getIfPresent("missing")).isNull();

        CacheStats stats = cache.stats();
        // recordStats が有効でないと CacheStats は常に 0 を返す
        assertThat(stats.hitCount()).isEqualTo(2);
        assertThat(stats.missCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("timedAspect Bean は MeterRegistry をラップする TimedAspect インスタンスを返す")
    void timed_aspect_is_wired_with_meter_registry() {
        CacheConfig config = new CacheConfig();
        MeterRegistry registry = new SimpleMeterRegistry();

        TimedAspect aspect = config.timedAspect(registry);

        assertThat(aspect).isNotNull();
    }
}
