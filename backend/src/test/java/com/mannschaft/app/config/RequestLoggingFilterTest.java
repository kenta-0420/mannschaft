package com.mannschaft.app.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.service.ErrorReportNotifier;
import com.mannschaft.app.errorreport.service.ErrorReportService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RequestLoggingFilter} の単体テスト。
 *
 * <p>F10.5 Phase 10-α §5.1.2 に従い、リクエスト完了時に duration_ms が MDC に積まれ、
 * 500ms / 2000ms / 10000ms の閾値で slow フラグやログレベルが切り替わることを検証する。</p>
 */
@DisplayName("RequestLoggingFilter (F10.5 Phase 10-α duration_ms 計測)")
class RequestLoggingFilterTest {

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
        // ルートロガーが test プロファイル（logback-spring.xml）で WARN に固定されているため、
        // INFO ログ (request_completed) が ListAppender に到達しない。テスト対象ロガーの
        // レベルを明示的に INFO に下げて、appender 側で全 level を捕捉できるようにする。
        // tearDown() で元に戻す。
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
    @DisplayName("通常リクエスト: duration_ms が MDC に積まれ INFO ログが出力される")
    void duration_ms_is_recorded_for_normal_request() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/todos");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> ((HttpServletResponse) res).setStatus(200);

        filter.doFilter(request, response, chain);

        // INFO レベルで request_completed ログが 1 件出ていること
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getMessage()).isEqualTo("request_completed");
        // MDC に duration_ms / method / path / status が含まれること
        assertThat(event.getMDCPropertyMap()).containsKey("duration_ms");
        assertThat(event.getMDCPropertyMap().get("method")).isEqualTo("GET");
        assertThat(event.getMDCPropertyMap().get("path")).isEqualTo("/api/v1/todos");
        assertThat(event.getMDCPropertyMap().get("status")).isEqualTo("200");
        // 通常リクエストでは slow フラグは付かない
        assertThat(event.getMDCPropertyMap()).doesNotContainKey("slow");
    }

    @Test
    @DisplayName("X-Request-Id ヘッダがあれば MDC に伝播し、レスポンスヘッダにも返却される")
    void request_id_header_is_propagated() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/foo");
        request.addHeader("X-Request-Id", "fixed-request-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {});

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("fixed-request-id");
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getMDCPropertyMap().get("requestId")).isEqualTo("fixed-request-id");
    }

    @Test
    @DisplayName("500ms 以上 2000ms 未満: slow=true フラグが MDC に付与される")
    void slow_flag_is_added_when_above_500ms() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/heavy");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> sleepQuietly(550);

        filter.doFilter(request, response, chain);

        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getMDCPropertyMap().get("slow")).isEqualTo("true");
        // duration_ms が 500 以上であること
        long durationMs = Long.parseLong(event.getMDCPropertyMap().get("duration_ms"));
        assertThat(durationMs).isGreaterThanOrEqualTo(500L);
    }

    @Test
    @DisplayName("2000ms 以上 10000ms 未満: WARN にエスカレーションされる")
    void warn_when_above_2s() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/very-heavy");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> sleepQuietly(2_050);

        filter.doFilter(request, response, chain);

        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        long durationMs = Long.parseLong(event.getMDCPropertyMap().get("duration_ms"));
        assertThat(durationMs).isGreaterThanOrEqualTo(2_000L);
    }

    @Test
    @DisplayName("MDC は filter 完了後にクリアされる")
    void mdc_is_cleared_after_filter() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/clear");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {});

        assertThat(MDC.get("duration_ms")).isNull();
        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("traceId")).isNull();
    }

    /**
     * テスト用の単純 sleep。InterruptedException を握り潰して FilterChain 内で使えるようにする。
     */
    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ============================================================
    // F10.5 Phase 10-β: ERROR 閾値超過時の Notifier / Service 連携
    // ============================================================

    @Test
    @DisplayName("ERROR 閾値超過: notifySlowRequest と recordBackendException(HIGH) が URI テンプレートで呼ばれる")
    void error_threshold_triggers_notifier_and_service() throws ServletException, IOException {
        // 閾値を低くして発火しやすくする（warn=10, error=20 ms）
        PerformanceMonitoringProperties props = new PerformanceMonitoringProperties();
        props.getRequest().setWarnMs(10L);
        props.getRequest().setErrorMs(20L);

        ErrorReportNotifier notifier = mock(ErrorReportNotifier.class);
        ErrorReportService service = mock(ErrorReportService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ErrorReportNotifier> notifierProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ErrorReportService> serviceProvider = mock(ObjectProvider.class);
        when(notifierProvider.getIfAvailable()).thenReturn(notifier);
        when(serviceProvider.getIfAvailable()).thenReturn(service);

        RequestLoggingFilter wired = new RequestLoggingFilter(props, notifierProvider, serviceProvider);

        // F10.5 Phase 10-β 後続-②: BEST_MATCHING_PATTERN_ATTRIBUTE をセットしてテンプレート化が効くか検証
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/123");
        request.setAttribute(
                org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/v1/users/{id}");
        request.addHeader("X-Request-Id", "rid-100");
        request.addHeader("User-Agent", "test-agent/1.0");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> sleepQuietly(50); // > error_ms=20

        wired.doFilter(request, response, chain);

        // ②: notifier には URI テンプレート（/api/v1/users/{id}）が渡る
        verify(notifier).notifySlowRequest(eq("GET"), eq("/api/v1/users/{id}"),
                anyLong(), eq("rid-100"));
        // ④: service は新オーバーロードで pageUrl=テンプレート / userAgent / requestId を直渡し
        verify(service).recordBackendException(
                any(RequestLoggingFilter.SlowRequestException.class),
                eq("/api/v1/users/{id}"),
                eq("test-agent/1.0"),
                anyString(), // ipAddress（MockHttpServletRequest が 127.0.0.1 を返す）
                eq("rid-100"),
                eq(ErrorReportSeverity.HIGH));
    }

    @Test
    @DisplayName("F10.5 Phase 10-β 後続-②: 同一テンプレートの異なる ID 間でクールダウンキーが共通化される")
    void cooldown_key_is_normalized_to_template() throws ServletException, IOException {
        // 実際の Caffeine クールダウンは ErrorReportNotifier 側に閉じている。
        // ここでは Filter が「URI テンプレートを notifier に渡す」ことを検証する
        // （= notifier 側で同一キーになり 1 分以内 2 回目以降は早期 return される）。
        PerformanceMonitoringProperties props = new PerformanceMonitoringProperties();
        props.getRequest().setWarnMs(10L);
        props.getRequest().setErrorMs(20L);

        ErrorReportNotifier notifier = mock(ErrorReportNotifier.class);
        ErrorReportService service = mock(ErrorReportService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ErrorReportNotifier> notifierProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ErrorReportService> serviceProvider = mock(ObjectProvider.class);
        when(notifierProvider.getIfAvailable()).thenReturn(notifier);
        when(serviceProvider.getIfAvailable()).thenReturn(service);

        RequestLoggingFilter wired = new RequestLoggingFilter(props, notifierProvider, serviceProvider);

        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/api/v1/users/123");
        req1.setAttribute(
                org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/v1/users/{id}");
        wired.doFilter(req1, new MockHttpServletResponse(), (q, s) -> sleepQuietly(50));

        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/api/v1/users/456");
        req2.setAttribute(
                org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/v1/users/{id}");
        wired.doFilter(req2, new MockHttpServletResponse(), (q, s) -> sleepQuietly(50));

        // 2 回とも同じテンプレート文字列で notifier が呼ばれた（=クールダウンキーが共通化される）ことを検証
        verify(notifier, times(2))
                .notifySlowRequest(eq("GET"), eq("/api/v1/users/{id}"), anyLong(), any());
    }

    @Test
    @DisplayName("F10.5 Phase 10-β 後続-②: BEST_MATCHING_PATTERN_ATTRIBUTE 未セット時は raw path にフォールバック")
    void fallback_to_raw_path_when_no_pattern_attribute() throws ServletException, IOException {
        PerformanceMonitoringProperties props = new PerformanceMonitoringProperties();
        props.getRequest().setWarnMs(10L);
        props.getRequest().setErrorMs(20L);

        ErrorReportNotifier notifier = mock(ErrorReportNotifier.class);
        ErrorReportService service = mock(ErrorReportService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ErrorReportNotifier> notifierProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ErrorReportService> serviceProvider = mock(ObjectProvider.class);
        when(notifierProvider.getIfAvailable()).thenReturn(notifier);
        when(serviceProvider.getIfAvailable()).thenReturn(service);

        RequestLoggingFilter wired = new RequestLoggingFilter(props, notifierProvider, serviceProvider);

        // BEST_MATCHING_PATTERN_ATTRIBUTE 未セット
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/static/asset.png");
        wired.doFilter(request, new MockHttpServletResponse(), (q, s) -> sleepQuietly(50));

        // raw path にフォールバック
        verify(notifier).notifySlowRequest(eq("GET"), eq("/static/asset.png"), anyLong(), any());
    }

    @Test
    @DisplayName("ERROR 閾値未満: notifier / service は呼ばれない")
    void below_threshold_does_not_trigger() throws ServletException, IOException {
        PerformanceMonitoringProperties props = new PerformanceMonitoringProperties();
        props.getRequest().setWarnMs(2_000L);
        props.getRequest().setErrorMs(10_000L); // 既定

        ErrorReportNotifier notifier = mock(ErrorReportNotifier.class);
        ErrorReportService service = mock(ErrorReportService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ErrorReportNotifier> notifierProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ErrorReportService> serviceProvider = mock(ObjectProvider.class);
        when(notifierProvider.getIfAvailable()).thenReturn(notifier);
        when(serviceProvider.getIfAvailable()).thenReturn(service);

        RequestLoggingFilter wired = new RequestLoggingFilter(props, notifierProvider, serviceProvider);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/light");
        MockHttpServletResponse response = new MockHttpServletResponse();
        wired.doFilter(request, response, (req, res) -> {});

        verify(notifier, never()).notifySlowRequest(anyString(), anyString(), anyLong(), any());
        verify(service, never()).recordBackendException(any(), any(HttpServletRequest.class), any());
        verify(service, never()).recordBackendException(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Bean 未配線（既定コンストラクタ）でも例外なく完走する")
    void unwired_filter_still_works() throws ServletException, IOException {
        // デフォルトコンストラクタ（既存テストと同等）
        RequestLoggingFilter unwired = new RequestLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/foo");
        MockHttpServletResponse response = new MockHttpServletResponse();
        unwired.doFilter(request, response, (req, res) -> {});
        // 例外を投げなければ OK
    }
}
