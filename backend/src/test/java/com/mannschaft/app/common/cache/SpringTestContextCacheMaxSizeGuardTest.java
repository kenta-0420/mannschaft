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
 * {@code tasks.withType<Test>} で system property として maxSize=10（既定）を設定している。</p>
 *
 * <p>検証対象は Spring 自身の {@link ContextCacheUtils#retrieveMaxCacheSize()}
 * （system property を読む実装）とし、Spring コンテキストは起動しない軽量な UT にする。</p>
 *
 * <p><b>偽 green 対策</b>: 期待値と実測値を同じ system property
 * {@code spring.test.context.cache.maxSize} から導くと、build.gradle.kts の既定値が
 * 誤って 32 に戻っても「期待値=実測値=32」で常に一致し、このガードは何も検出できない。
 * そのため、上書き運用（{@code -Pspring.test.context.cache.maxSize=N}）でない限り、
 * 期待値はテストに直書きした独立の定数 {@link #EXPECTED_DEFAULT_MAX_SIZE}（= 10。
 * CMP-261002-1606 shard0 MAT で Spring コンテキスト17個2.48GB→上限10 と決めた根拠値）
 * と比較する。build.gradle.kts の既定値が変われば、この定数との不一致でガードが落ちる。
 * 上書きが明示されたときのみ、実測値を system property の値と比較する
 * （上書き運用時は何が渡されたか分からないため、定数とは比較できない）。</p>
 */
class SpringTestContextCacheMaxSizeGuardTest {

    /**
     * CMP-261002-1606 shard0 MAT 実測に基づく既定上限の期待値。
     * build.gradle.kts の既定値（contextCacheMaxSizeOverride ?: "10"）と独立に直書きする
     * ことで、既定値が誤って 32 に戻った場合にこのガードが検出できるようにする。
     */
    private static final int EXPECTED_DEFAULT_MAX_SIZE = 10;

    @Test
    @DisplayName("spring.test.context.cache.maxSize の system property がテスト JVM に届き、Spring がその値を読む")
    void maxSizeSystemPropertyReachesTheTestJvm() {
        String configured = System.getProperty("spring.test.context.cache.maxSize");
        String overridden = System.getProperty("mannschaft.test.contextCacheMaxSize.overridden");

        assertThat(configured)
                .as("backend/build.gradle.kts の systemProperty 設定が欠落していないこと"
                        + "（Gradle 外から直接このテストを起動していないこと）")
                .isNotNull();
        assertThat(overridden)
                .as("backend/build.gradle.kts の mannschaft.test.contextCacheMaxSize.overridden"
                        + " systemProperty 設定が欠落していないこと")
                .isNotNull();

        boolean isOverridden = Boolean.parseBoolean(overridden);

        if (isOverridden) {
            // -Pspring.test.context.cache.maxSize=N による明示上書き運用。
            // この場合は何を渡されたか分からないため、system property の値同士を比較する。
            int expected = Integer.parseInt(configured);
            assertThat(ContextCacheUtils.retrieveMaxCacheSize())
                    .as("上書き時は Spring が渡された system property の値をそのまま読み取ること")
                    .isEqualTo(expected);
        } else {
            // 既定運用。期待値はテストに直書きした独立の定数と比較する。
            // build.gradle.kts の既定値が誤って 32 に戻った場合、configured は "32" になり、
            // EXPECTED_DEFAULT_MAX_SIZE(=10) との不一致でここが落ちる。
            assertThat(Integer.parseInt(configured))
                    .as("既定運用では build.gradle.kts の既定値が CMP-261002-1606 の根拠値(10)のままであること")
                    .isEqualTo(EXPECTED_DEFAULT_MAX_SIZE);
            assertThat(ContextCacheUtils.retrieveMaxCacheSize())
                    .as("既定運用では Spring が CMP-261002-1606 の根拠値(10)を読み取ること")
                    .isEqualTo(EXPECTED_DEFAULT_MAX_SIZE);
        }
    }
}
