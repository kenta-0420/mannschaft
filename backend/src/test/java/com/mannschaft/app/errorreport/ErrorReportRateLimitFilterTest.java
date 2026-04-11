package com.mannschaft.app.errorreport;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ErrorReportRateLimitFilter} のユニットテスト。
 *
 * <p>Caffeine キャッシュ化によるメモリリーク修正の回帰防止を兼ねる。
 * Bucket4j のバケット使用率は本体の振る舞いでカバーし、
 * Caffeine の TTL は {@link com.github.benmanes.caffeine.cache.Cache#invalidateAll()} で
 * 経過後の挙動をシミュレートする。</p>
 */
class ErrorReportRateLimitFilterTest {

    private ErrorReportRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ErrorReportRateLimitFilter();
    }

    private MockHttpServletRequest postErrorReport(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/error-reports");
        request.setServletPath("/api/v1/error-reports");
        request.setRemoteAddr(ip);
        return request;
    }

    private MockHttpServletResponse invoke(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilterInternal(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("同一 IP から 10 回までは通過、11 回目で 429")
    void exceedsLimitReturns429() throws Exception {
        String ip = "10.0.0.1";

        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = invoke(postErrorReport(ip));
            assertThat(response.getStatus())
                    .as("error-reports POST #%d should pass", i + 1)
                    .isEqualTo(HttpStatus.OK.value());
        }

        MockHttpServletResponse overLimit = invoke(postErrorReport(ip));
        assertThat(overLimit.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("異なる IP はバケットが独立しており相互に影響しない")
    void bucketsAreIsolatedByIp() throws Exception {
        String ipA = "10.0.0.2";
        String ipB = "10.0.0.3";

        // ipA を限界まで消費
        for (int i = 0; i < 10; i++) {
            assertThat(invoke(postErrorReport(ipA)).getStatus()).isEqualTo(HttpStatus.OK.value());
        }
        assertThat(invoke(postErrorReport(ipA)).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        // ipB は独立して 10 回まで通過する
        for (int i = 0; i < 10; i++) {
            assertThat(invoke(postErrorReport(ipB)).getStatus())
                    .as("ipB error-reports POST #%d should pass", i + 1)
                    .isEqualTo(HttpStatus.OK.value());
        }
    }

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
            assertThat(invoke(postErrorReport(ip)).getStatus()).isEqualTo(HttpStatus.OK.value());
        }
        assertThat(invoke(postErrorReport(ip)).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        // TTL 経過を invalidateAll() で模擬
        invalidateAllBuckets();

        // 新しいバケットが払い出され、再び通過できる
        assertThat(invoke(postErrorReport(ip)).getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    private void invalidateAllBuckets() throws Exception {
        Field bucketsField = ErrorReportRateLimitFilter.class.getDeclaredField("buckets");
        bucketsField.setAccessible(true);
        com.github.benmanes.caffeine.cache.Cache<?, ?> cache =
                (com.github.benmanes.caffeine.cache.Cache<?, ?>) bucketsField.get(filter);
        cache.invalidateAll();
    }
}
