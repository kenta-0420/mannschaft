package com.mannschaft.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 試練（CMP-260826-1457 W0・§2-2 の 7.・AC-10b の単体側）: {@link RequestLoggingFilter} が MDC {@code path} に積む値。
 *
 * <ul>
 *   <li>Spring MVC がハンドラを解決したとき: ルート テンプレート（{@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE}）を積む</li>
 *   <li>テンプレートが無いとき（404・ハンドラ解決前の応答）: 生の URI のセグメントを固定記号に置き換えた値を積み、
 *       パスに含まれていた値（平文の token を含む）を残さない</li>
 * </ul>
 *
 * <p>実フィルタ連鎖を通す検証は {@link RequestLoggingFilterTokenPathLeakIT}。本クラスは判定だけを速く固定する。</p>
 *
 * <p>red の理由: 現行は {@code request.getRequestURI()} をそのまま積む。</p>
 */
@DisplayName("試練 CMP-260826-1457: RequestLoggingFilter は path にルート テンプレートを積み、平文を残さない")
class RequestLoggingFilterRouteTemplateTest {

    private static final String PLAINTEXT = "cmp1457PlaintextProbeAbCdEf0123456789";

    private RequestLoggingFilter filter;

    private ListAppender<ILoggingEvent> appender;

    private Logger filterLogger;

    private Level originalLevel;

    @BeforeEach
    void setUp() {
        filter = new RequestLoggingFilter();
        filterLogger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        appender = new ListAppender<>();
        appender.start();
        filterLogger.addAppender(appender);
        originalLevel = filterLogger.getLevel();
        filterLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        filterLogger.setLevel(originalLevel);
        filterLogger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    @DisplayName("ハンドラ解決済み: path はルート テンプレート（/api/v1/invite/{token}/join）になる")
    void ハンドラ解決済みならpathはルートテンプレート() throws Exception {
        String template = "/api/v1/invite/{token}/join";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/invite/" + PLAINTEXT + "/join");
        // DispatcherServlet がハンドラを解決したときと同じく、連鎖の中で属性が積まれる
        FilterChain chain = (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, template);
            ((HttpServletResponse) res).setStatus(HttpServletResponse.SC_OK);
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(completedPaths()).containsExactly(template);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "/api/v1/cmp1457-no-such-route/" + PLAINTEXT,
            "/ical/" + PLAINTEXT + ".ics",
            "/incoming/" + PLAINTEXT,
            "/api/v1/invite/" + PLAINTEXT + "/cmp1457-no-such-child",
    })
    @DisplayName("テンプレート無し（404 等）: path に生のセグメント値を残さない")
    void テンプレートが無ければセグメント値を残さない(String rawUri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", rawUri);
        FilterChain chain = (req, res) -> ((HttpServletResponse) res).setStatus(HttpServletResponse.SC_NOT_FOUND);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        List<String> paths = completedPaths();
        assertThat(paths).as("request_completed が1件出ること").hasSize(1);
        assertThat(paths.get(0))
                .as("path は空にしない（どの種類のリクエストかの手がかりは残す）")
                .isNotBlank()
                .as("path に平文（パスに含まれていた値）を残さない")
                .doesNotContain(PLAINTEXT);
    }

    @Test
    @DisplayName("秘密でない path 変数の許可リスト: 数字だけの {token} は pathIds に積まず、{teamId} は積む")
    void 数字だけのtoken変数はpathIdsに積まない() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/teams/7/invite/123456789");
        FilterChain chain = (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/teams/{teamId}/invite/{token}");
            req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                    java.util.Map.of("teamId", "7", "token", "123456789"));
            ((HttpServletResponse) res).setStatus(HttpServletResponse.SC_OK);
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        ILoggingEvent event = appender.list.stream()
                .filter(e -> "request_completed".equals(e.getMessage())).findFirst().orElseThrow();
        assertThat(event.getMDCPropertyMap().get("pathIds")).isEqualTo("teamId=7");
        assertThat(event.getMDCPropertyMap().values()).noneMatch(v -> v != null && v.contains("123456789"));
    }

    private List<String> completedPaths() {
        return appender.list.stream()
                .filter(event -> "request_completed".equals(event.getMessage()))
                .map(event -> event.getMDCPropertyMap().get("path"))
                .toList();
    }
}
