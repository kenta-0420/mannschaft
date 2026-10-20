package com.mannschaft.app.loyalty;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

/** 実Servletフィルターでバイト境界と下流への本文再生を検証する。 */
class LoyaltyBodyLimitFilterTest {
    private final LoyaltyBodyLimitFilter filter = new LoyaltyBodyLimitFilter(new ObjectMapper());

    @Test void AC18_4096バイトを変更せず下流へ渡す() throws Exception {
        byte[] body = ("あ".repeat(1365) + " ").getBytes(StandardCharsets.UTF_8);
        var received = new AtomicReference<byte[]>();
        var response = apply(request(body, true), (req, res) -> received.set(req.getInputStream().readAllBytes()));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(received.get()).containsExactly(body);
    }

    @Test void AC18_readerもUTF8本文を再生する() throws Exception {
        String body = "あ".repeat(100);
        var received = new AtomicReference<String>();
        apply(request(body.getBytes(StandardCharsets.UTF_8), true),
                (req, res) -> received.set(req.getReader().readLine()));
        assertThat(received.get()).isEqualTo(body);
    }

    @Test void AC18_宣言された4097バイトを業務呼出前に拒否する() throws Exception {
        rejected(request(new byte[4097], true));
    }

    @Test void AC18_長さ不明の4097バイトも業務呼出前に拒否する() throws Exception {
        rejected(request(new byte[4097], false));
    }

    @Test void AC18_文字数ではなくUTF8バイトで上限を判定する() throws Exception {
        rejected(request("あ".repeat(1366).getBytes(StandardCharsets.UTF_8), false));
    }

    @Test void AC18_別機能の本文制限を変更しない() throws Exception {
        var request = request(new byte[5000], false);
        request.setRequestURI("/api/v1/teams/1/budgets");
        var invoked = new AtomicBoolean();
        apply(request, (req, res) -> invoked.set(true));
        assertThat(invoked).isTrue();
    }

    @Test void AC18_本人経路にも同じ上限を適用する() throws Exception {
        var request = request(new byte[4097], false);
        request.setRequestURI("/api/v1/loyalty/self-award-bootstrap/me");
        rejected(request);
    }

    private void rejected(MockHttpServletRequest request) throws Exception {
        var invoked = new AtomicBoolean();
        var response = apply(request, (req, res) -> invoked.set(true));
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(invoked).isFalse();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var json = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertThat(json.path("error").path("code").asText()).isEqualTo("COMMON_001");
        assertThat(json.path("error").path("message").isTextual()).isTrue();
    }

    private MockHttpServletResponse apply(MockHttpServletRequest request, FilterChain chain) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private MockHttpServletRequest request(byte[] body, boolean knownLength) {
        var request = new MockHttpServletRequest() {
            @Override public int getContentLength() { return knownLength ? body.length : -1; }
            @Override public long getContentLengthLong() { return knownLength ? body.length : -1; }
        };
        request.setMethod("POST");
        request.setRequestURI("/api/v1/teams/1/loyalty/awards");
        request.setCharacterEncoding("UTF-8");
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }
}
