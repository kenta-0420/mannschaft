package com.mannschaft.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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

    /** 500ms 以上で slow フラグを立てる閾値 */
    static final long SLOW_THRESHOLD_MS = 500L;
    /** 2000ms 以上で WARN にエスカレートする閾値 */
    static final long WARN_THRESHOLD_MS = 2_000L;
    /** 10000ms 以上で ERROR にエスカレートする閾値 */
    static final long ERROR_THRESHOLD_MS = 10_000L;

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
            try {
                // F10.5 Phase 10-α: 計測結果を MDC に積み、構造化ログとして出力
                MDC.put(MDC_DURATION_MS, Long.toString(durationMs));
                MDC.put(MDC_METHOD, request.getMethod());
                // クエリ文字列は PII リーク防止のため記録しない（設計書 §6.2）
                MDC.put(MDC_PATH, request.getRequestURI());
                MDC.put(MDC_STATUS, Integer.toString(response.getStatus()));

                if (durationMs >= ERROR_THRESHOLD_MS) {
                    log.error("request_completed");
                } else if (durationMs >= WARN_THRESHOLD_MS) {
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
        }
    }
}
