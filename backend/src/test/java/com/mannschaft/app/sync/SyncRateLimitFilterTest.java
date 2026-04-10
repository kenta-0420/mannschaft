package com.mannschaft.app.sync;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SyncRateLimitFilter} のユニットテスト。
 *
 * <p>Caffeine キャッシュ化によるメモリリーク修正の回帰防止を兼ねる。
 * Bucket4j のバケット使用率は本体の振る舞いでカバーし、
 * Caffeine の TTL は {@link com.github.benmanes.caffeine.cache.Cache#invalidateAll()} で
 * 経過後の挙動をシミュレートする。</p>
 */
class SyncRateLimitFilterTest {

    private SyncRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SyncRateLimitFilter();
    }

    private MockHttpServletRequest syncPost(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sync");
        request.setServletPath("/api/v1/sync");
        request.setRemoteAddr(ip);
        return request;
    }

    private MockHttpServletRequest conflictGet(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/sync/conflicts");
        request.setServletPath("/api/v1/sync/conflicts");
        request.setRemoteAddr(ip);
        return request;
    }

    private MockHttpServletResponse invoke(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilterInternal(request, response, chain);
        return response;
    }

    @Nested
    @DisplayName("POST /api/v1/sync — 1分10回制限")
    class SyncPostLimit {

        @Test
        @DisplayName("同一 IP から 10 回までは通過、11 回目で 429")
        void syncPostExceedsLimit() throws Exception {
            String ip = "10.0.0.1";

            // 10 回までは全て OK
            for (int i = 0; i < 10; i++) {
                MockHttpServletResponse response = invoke(syncPost(ip));
                assertThat(response.getStatus())
                        .as("sync POST #%d should pass", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }

            // 11 回目で 429
            MockHttpServletResponse overLimit = invoke(syncPost(ip));
            assertThat(overLimit.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        }

        @Test
        @DisplayName("異なる IP はバケットが独立しており相互に影響しない")
        void syncPostIsolatedByIp() throws Exception {
            String ipA = "10.0.0.2";
            String ipB = "10.0.0.3";

            // ipA を限界まで消費
            for (int i = 0; i < 10; i++) {
                assertThat(invoke(syncPost(ipA)).getStatus()).isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(syncPost(ipA)).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            // ipB は独立して 10 回まで通過する
            for (int i = 0; i < 10; i++) {
                assertThat(invoke(syncPost(ipB)).getStatus())
                        .as("ipB sync POST #%d should pass", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }
        }
    }

    @Nested
    @DisplayName("GET /api/v1/sync/conflicts — 1分60回制限")
    class ConflictsLimit {

        @Test
        @DisplayName("同一 IP から 60 回までは通過、61 回目で 429")
        void conflictGetExceedsLimit() throws Exception {
            String ip = "10.0.1.1";

            for (int i = 0; i < 60; i++) {
                MockHttpServletResponse response = invoke(conflictGet(ip));
                assertThat(response.getStatus())
                        .as("conflicts GET #%d should pass", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }

            assertThat(invoke(conflictGet(ip)).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        }

        @Test
        @DisplayName("sync POST と conflicts GET のバケットは別キーで管理される")
        void syncAndConflictsAreSeparate() throws Exception {
            String ip = "10.0.1.2";

            // sync POST を使い切る
            for (int i = 0; i < 10; i++) {
                assertThat(invoke(syncPost(ip)).getStatus()).isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(syncPost(ip)).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            // conflicts は独立しているため引き続き通過する
            assertThat(invoke(conflictGet(ip)).getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }

    @Nested
    @DisplayName("バケット寿命（Caffeine キャッシュ）")
    class BucketEviction {

        /**
         * {@code invalidateAll()} で TTL 経過後のエントリ消失を模擬し、
         * 新しいバケットが再生成されてカウンタがリセットされることを確認する。
         */
        @Test
        @DisplayName("キャッシュが無効化されるとバケットがリセットされる（TTL 経過相当）")
        void bucketResetsAfterCacheEviction() throws Exception {
            String ip = "10.0.2.1";

            // 限界まで消費
            for (int i = 0; i < 10; i++) {
                assertThat(invoke(syncPost(ip)).getStatus()).isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(syncPost(ip)).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            // TTL 経過を invalidateAll() で模擬
            invalidateAllBuckets();

            // 新しいバケットが払い出され、再び通過できる
            assertThat(invoke(syncPost(ip)).getStatus()).isEqualTo(HttpStatus.OK.value());
        }

        @SuppressWarnings("unchecked")
        private void invalidateAllBuckets() throws Exception {
            Field syncField = SyncRateLimitFilter.class.getDeclaredField("syncBuckets");
            syncField.setAccessible(true);
            com.github.benmanes.caffeine.cache.Cache<String, ?> syncCache =
                    (com.github.benmanes.caffeine.cache.Cache<String, ?>) syncField.get(filter);
            syncCache.invalidateAll();

            Field conflictField = SyncRateLimitFilter.class.getDeclaredField("conflictBuckets");
            conflictField.setAccessible(true);
            com.github.benmanes.caffeine.cache.Cache<String, ?> conflictCache =
                    (com.github.benmanes.caffeine.cache.Cache<String, ?>) conflictField.get(filter);
            conflictCache.invalidateAll();
        }
    }
}
