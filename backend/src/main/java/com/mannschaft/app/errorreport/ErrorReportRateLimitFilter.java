package com.mannschaft.app.errorreport;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * エラーレポートAPIのIPベースレート制限フィルタ。
 * /api/v1/error-reports (POST) のみに適用し、他APIに影響しない。
 * 同一IPから10回/分。超過時は 429 Too Many Requests。
 *
 * <p>ConcurrentHashMap ベースのインメモリ実装。単一インスタンス環境では十分機能し、
 * Bucket4j-Redis（ProxyManager）設定が整い次第差し替え可能。</p>
 */
@Component
@RequiredArgsConstructor
public class ErrorReportRateLimitFilter extends OncePerRequestFilter {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/api/v1/error-reports".equals(request.getServletPath())
                || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String key = "error-report-rate:" + request.getRemoteAddr();
        Bucket bucket = resolveBucket(key);

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        }
    }

    private Bucket resolveBucket(String key) {
        return buckets.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(Bandwidth.simple(10, Duration.ofMinutes(1)))
                .build());
    }
}
