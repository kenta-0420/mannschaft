package com.mannschaft.app.config;

import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.service.ErrorReportNotifier;
import com.mannschaft.app.errorreport.service.ErrorReportService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * リクエストごとに MDC へ requestId / traceId を設定し、レスポンス完了時に
 * 経過時間 (duration_ms) を計測してログ出力するフィルター。
 *
 * <p>X-Request-Id ヘッダーが存在すればその値を使用し、
 * なければ UUID を自動生成する。ログ出力時のリクエスト追跡に利用する。</p>
 *
 * <p>F10.5 Phase 10-α (§5.1.2):</p>
 * <ul>
 *   <li>レスポンス完了時に duration_ms を計算し MDC に積む</li>
 *   <li>500ms 以上 2000ms 未満は INFO + slow=true フラグ</li>
 *   <li>2000ms 以上 10000ms 未満は WARN</li>
 *   <li>10000ms 以上は ERROR</li>
 * </ul>
 *
 * <p>具体的な Slack 通知連携は Phase 10-β（F10.6 ErrorReportNotifier 連携）で実装する。
 * 本フェーズではログ出力までを責務とする。</p>
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MDC_REQUEST_ID = "requestId";
    private static final String MDC_TRACE_ID = "traceId";
    private static final String MDC_DURATION_MS = "duration_ms";
    private static final String MDC_METHOD = "method";
    private static final String MDC_PATH = "path";
    private static final String MDC_STATUS = "status";
    private static final String MDC_SLOW_FLAG = "slow";

    /** 500ms 以上で slow フラグを立てる閾値（INFO + slow=true） */
    static final long SLOW_THRESHOLD_MS = 500L;

    /**
     * F10.5 Phase 10-β — WARN / ERROR 閾値は {@link PerformanceMonitoringProperties} から注入。
     * Phase 10-α 時の固定値（2000 / 10000）は同 properties のデフォルトとして維持。
     */
    private final PerformanceMonitoringProperties properties;

    /**
     * F10.5 Phase 10-β / F10.6 Phase 10-β-1 — スローリクエスト Slack 通知。
     * フィルターは {@link OncePerRequestFilter} として早期に Bean 化される一方、
     * {@link ErrorReportNotifier} / {@link ErrorReportService} は重い依存を持つ可能性があるため
     * {@link ObjectProvider} 経由で遅延解決する（循環参照の予防）。
     */
    private final ObjectProvider<ErrorReportNotifier> errorReportNotifierProvider;

    /**
     * F10.5 Phase 10-β / F10.6 Phase 10-β-1 — error_reports への記録。
     */
    private final ObjectProvider<ErrorReportService> errorReportServiceProvider;

    /**
     * Phase 10-α 互換コンストラクタ（テスト用：notifier/service を使わないケース）。
     * Spring の自動配線では使用されない。
     */
    public RequestLoggingFilter() {
        this.properties = new PerformanceMonitoringProperties();
        this.errorReportNotifierProvider = null;
        this.errorReportServiceProvider = null;
    }

    /**
     * Spring から自動配線されるコンストラクタ。
     */
    public RequestLoggingFilter(PerformanceMonitoringProperties properties,
                                ObjectProvider<ErrorReportNotifier> errorReportNotifierProvider,
                                ObjectProvider<ErrorReportService> errorReportServiceProvider) {
        this.properties = properties;
        this.errorReportNotifierProvider = errorReportNotifierProvider;
        this.errorReportServiceProvider = errorReportServiceProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        long startNanos = System.nanoTime();
        try {
            // X-Request-Id ヘッダーがあればそれを使用、なければ UUID 生成
            String requestId = request.getHeader(REQUEST_ID_HEADER);
            if (requestId == null || requestId.isBlank()) {
                requestId = UUID.randomUUID().toString();
            }

            String traceId = UUID.randomUUID().toString();

            MDC.put(MDC_REQUEST_ID, requestId);
            MDC.put(MDC_TRACE_ID, traceId);

            // レスポンスヘッダーにも requestId を返却（デバッグ用）
            response.setHeader(REQUEST_ID_HEADER, requestId);

            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            String method = request.getMethod();
            String path = request.getRequestURI();
            String requestId = MDC.get(MDC_REQUEST_ID);

            long warnMs = properties.getRequest().getWarnMs();
            long errorMs = properties.getRequest().getErrorMs();

            try {
                // F10.5 Phase 10-α: 計測結果を MDC に積み、構造化ログとして出力
                MDC.put(MDC_DURATION_MS, Long.toString(durationMs));
                MDC.put(MDC_METHOD, method);
                // クエリ文字列は PII リーク防止のため記録しない（設計書 §6.2）
                MDC.put(MDC_PATH, path);
                MDC.put(MDC_STATUS, Integer.toString(response.getStatus()));

                if (durationMs >= errorMs) {
                    log.error("request_completed");
                } else if (durationMs >= warnMs) {
                    log.warn("request_completed");
                } else if (durationMs >= SLOW_THRESHOLD_MS) {
                    MDC.put(MDC_SLOW_FLAG, "true");
                    log.info("request_completed");
                } else {
                    log.info("request_completed");
                }
            } finally {
                MDC.clear();
            }

            // F10.5 Phase 10-β: error_ms 超過は Slack 通知 + error_reports に severity=HIGH で記録
            // ログ出力後・MDC クリア後に実施することで、内部処理の例外で MDC が漏れないようにする
            if (durationMs >= errorMs) {
                fireSlowRequestAlert(method, path, durationMs, requestId);
            }
        }
    }

    /**
     * F10.5 Phase 10-β: 10秒超のリクエストに対して
     * {@link ErrorReportNotifier#notifySlowRequest(String, String, long, String)} と
     * {@link ErrorReportService#recordBackendException(Throwable, HttpServletRequest, ErrorReportSeverity)}
     * を呼び出す。Bean 未配線（テストなど）の場合は静かにスキップする。
     */
    private void fireSlowRequestAlert(String method, String path, long durationMs, String requestId) {
        try {
            ErrorReportNotifier notifier = errorReportNotifierProvider != null
                    ? errorReportNotifierProvider.getIfAvailable()
                    : null;
            if (notifier != null) {
                notifier.notifySlowRequest(method, path, durationMs, requestId);
            }

            ErrorReportService service = errorReportServiceProvider != null
                    ? errorReportServiceProvider.getIfAvailable()
                    : null;
            if (service != null) {
                // 設計書 F10.5 §5.1.2 / F10.6 §5.2: 遅延リクエストは severity=HIGH で記録
                SlowRequestException synthetic = new SlowRequestException(
                        String.format("Request took %d ms (threshold=%d): %s %s",
                                durationMs, properties.getRequest().getErrorMs(), method, path));
                service.recordBackendException(synthetic, null, ErrorReportSeverity.HIGH);
            }
        } catch (Exception e) {
            // 通知・記録の失敗でリクエスト本体に影響を出さない
            log.warn("Slow request alert failed: method={}, path={}, durationMs={}",
                    method, path, durationMs, e);
        }
    }

    /**
     * F10.5 Phase 10-β — スローリクエスト記録用の合成例外。
     * {@code error_reports.error_message} に専用識別を残すため独立クラスとする。
     */
    public static class SlowRequestException extends RuntimeException {
        public SlowRequestException(String message) {
            super(message);
        }
    }
}
