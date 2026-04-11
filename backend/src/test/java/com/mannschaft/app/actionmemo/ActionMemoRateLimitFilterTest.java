package com.mannschaft.app.actionmemo;

import com.github.benmanes.caffeine.cache.Cache;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ActionMemoRateLimitFilter} のユニットテスト。
 *
 * <p>Caffeine キャッシュ化によるメモリリーク修正の回帰防止を兼ねる。
 * Bucket4j のバケット使用率は本体の振る舞いでカバーし、
 * Caffeine の TTL は {@link Cache#invalidateAll()} で経過後の挙動をシミュレートする。</p>
 */
class ActionMemoRateLimitFilterTest {

    private ActionMemoRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ActionMemoRateLimitFilter();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse invoke(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilterInternal(request, response, chain);
        return response;
    }

    private MockHttpServletRequest request(String method, String path, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setRemoteAddr(ip);
        return request;
    }

    private void authenticateAs(String userId) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userId, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Nested
    @DisplayName("POST /api/v1/action-memos — 60 req/分")
    class CreateMemoLimit {

        @Test
        @DisplayName("同一 IP から 60 回までは通過、61 回目で 429 と Retry-After ヘッダ")
        void exceedsLimitReturns429() throws Exception {
            String ip = "10.0.0.1";

            for (int i = 0; i < 60; i++) {
                MockHttpServletResponse response = invoke(request("POST", "/api/v1/action-memos", ip));
                assertThat(response.getStatus())
                        .as("action-memos POST #%d should pass", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }

            MockHttpServletResponse overLimit = invoke(request("POST", "/api/v1/action-memos", ip));
            assertThat(overLimit.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(overLimit.getHeader("Retry-After")).isEqualTo("60");
        }

        @Test
        @DisplayName("異なる IP はバケットが独立する")
        void isolatedByIp() throws Exception {
            String ipA = "10.0.0.2";
            String ipB = "10.0.0.3";

            for (int i = 0; i < 60; i++) {
                assertThat(invoke(request("POST", "/api/v1/action-memos", ipA)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("POST", "/api/v1/action-memos", ipA)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            // ipB は独立
            assertThat(invoke(request("POST", "/api/v1/action-memos", ipB)).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
        }

        @Test
        @DisplayName("認証済みユーザーは userId 単位でカウントされる")
        void authenticatedUserKeyedByUserId() throws Exception {
            authenticateAs("user-alice");

            for (int i = 0; i < 60; i++) {
                assertThat(invoke(request("POST", "/api/v1/action-memos", "10.0.0.9")).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("POST", "/api/v1/action-memos", "10.0.0.9")).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            // 同じ IP でも別ユーザーなら通る
            authenticateAs("user-bob");
            assertThat(invoke(request("POST", "/api/v1/action-memos", "10.0.0.9")).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/action-memos/publish-daily — 5 req/分")
    class PublishDailyLimit {

        @Test
        @DisplayName("同一 IP から 5 回までは通過、6 回目で 429")
        void exceedsLimit() throws Exception {
            String ip = "10.0.1.1";

            for (int i = 0; i < 5; i++) {
                assertThat(invoke(request("POST", "/api/v1/action-memos/publish-daily", ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("POST", "/api/v1/action-memos/publish-daily", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/action-memo-tags — 20 req/分")
    class CreateTagLimit {

        @Test
        @DisplayName("同一 IP から 20 回までは通過、21 回目で 429")
        void exceedsLimit() throws Exception {
            String ip = "10.0.2.1";

            for (int i = 0; i < 20; i++) {
                assertThat(invoke(request("POST", "/api/v1/action-memo-tags", ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("POST", "/api/v1/action-memo-tags", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/action-memo-settings — 10 req/分")
    class UpdateSettingsLimit {

        @Test
        @DisplayName("同一 IP から 10 回までは通過、11 回目で 429")
        void exceedsLimit() throws Exception {
            String ip = "10.0.3.1";

            for (int i = 0; i < 10; i++) {
                assertThat(invoke(request("PATCH", "/api/v1/action-memo-settings", ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("PATCH", "/api/v1/action-memo-settings", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        }
    }

    @Nested
    @DisplayName("エンドポイント間のバケット分離")
    class EndpointIsolation {

        @Test
        @DisplayName("create-memo を使い切っても publish-daily は独立して通る")
        void endpointsAreIndependent() throws Exception {
            String ip = "10.0.4.1";

            // create-memo を上限まで消費
            for (int i = 0; i < 60; i++) {
                assertThat(invoke(request("POST", "/api/v1/action-memos", ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("POST", "/api/v1/action-memos", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            // publish-daily は独立しており通過する
            assertThat(invoke(request("POST", "/api/v1/action-memos/publish-daily", ip)).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            // create-tag も独立
            assertThat(invoke(request("POST", "/api/v1/action-memo-tags", ip)).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            // update-settings も独立
            assertThat(invoke(request("PATCH", "/api/v1/action-memo-settings", ip)).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
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
            String ip = "10.0.5.1";

            for (int i = 0; i < 60; i++) {
                assertThat(invoke(request("POST", "/api/v1/action-memos", ip)).getStatus())
                        .isEqualTo(HttpStatus.OK.value());
            }
            assertThat(invoke(request("POST", "/api/v1/action-memos", ip)).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            invalidateAllBuckets();

            // 新しいバケットが払い出されて再通過できる
            assertThat(invoke(request("POST", "/api/v1/action-memos", ip)).getStatus())
                    .isEqualTo(HttpStatus.OK.value());
        }

        @SuppressWarnings("unchecked")
        private void invalidateAllBuckets() throws Exception {
            Field field = ActionMemoRateLimitFilter.class.getDeclaredField("bucketsByEndpoint");
            field.setAccessible(true);
            Map<?, Cache<String, ?>> map = (Map<?, Cache<String, ?>>) field.get(filter);
            for (Cache<String, ?> cache : map.values()) {
                cache.invalidateAll();
            }
        }
    }
}
