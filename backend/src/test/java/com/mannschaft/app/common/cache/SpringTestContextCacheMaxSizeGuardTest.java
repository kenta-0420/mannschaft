package com.mannschaft.app.common.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.cache.ContextCacheUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring テストコンテキストキャッシュ上限（CMP-261002-1606・PR #3620）が
 * 実際にテスト JVM へ届いていることを検証する番人。
 *
 * <p>MAT 実測（run 37196327170）で shard 0 の OOM 時、ヒープの 59%（2.48GB）を
 * Spring テストコンテキスト 17 個（平均約146MB・最大177MB）が占めていた。
 * {@code spring.test.context.cache.maxSize} は未設定で既定 32 のため、テスト構成の
 * 種類が多いと 1 JVM に十数個が溜まり -Xmx4g を超える。backend/build.gradle.kts の
 * {@code tasks.withType<Test>} で system property として maxSize=10 を設定している。</p>
 *
 * <p>検証対象は Spring 自身の {@link ContextCacheUtils#retrieveMaxCacheSize()}
 * （system property を読む実装）とし、Spring コンテキストは起動しない軽量な UT にする。
 * {@code -Pspring.test.context.cache.maxSize=N} で上書きされる運用を想定し、
 * 期待値は固定の 10 ではなく「このテスト JVM に実際に渡された system property の値」と
 * 一致することを確かめる（上書き運用時にも緑のまま保てる）。</p>
 */
class SpringTestContextCacheMaxSizeGuardTest {

    @Test
    @DisplayName("spring.test.context.cache.maxSize の system property がテスト JVM に届き、Spring がその値を読む")
    void maxSizeSystemPropertyReachesTheTestJvm() {
        String configured = System.getProperty("spring.test.context.cache.maxSize");

        assertThat(configured)
                .as("backend/build.gradle.kts の systemProperty 設定が欠落していないこと")
                .isNotNull();

        int expected = Integer.parseInt(configured);
        assertThat(ContextCacheUtils.retrieveMaxCacheSize())
                .as("Spring が同じ system property から上限値を読み取ること")
                .isEqualTo(expected);
    }
}
