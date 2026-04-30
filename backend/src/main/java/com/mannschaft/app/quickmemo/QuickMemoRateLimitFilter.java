package com.mannschaft.app.quickmemo;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * ポイッとメモ機能のユーザー別レートリミットフィルタ。
 *
 * <p>以下のエンドポイントに対してユーザー単位のレートリミットを適用する:</p>
 * <ul>
 *   <li>CRUD 操作 ({@code /api/v1/quick-memos/**}): 60 req/分</li>
 *   <li>添付ファイル操作 ({@code /api/v1/quick-memos/{id}/attachments/presign}, {@code /confirm}): 10 req/分</li>
 *   <li>タグ操作 ({@code /api/v1/me/tags}, {@code /api/v1/teams/*/tags}, {@code /api/v1/organizations/*/tags}): 20 req/分</li>
 * </ul>
 *
 * <p><b>キャッシュ戦略</b>: Caffeine の {@code expireAfterAccess=10分} + {@code maximumSize=10000}。
 * {@link com.mannschaft.app.actionmemo.ActionMemoRateLimitFilter} と同一パターン。</p>
 */
@Component
public class QuickMemoRateLimitFilter extends OncePerRequestFilter {

    /** CRUD操作のレート制限 (req/分) */
    private static final int CRUD_RATE_PER_MINUTE = 60;

    /** 添付ファイル操作のレート制限 (req/分) */
    private static final int ATTACHMENT_RATE_PER_MINUTE = 10;

    /** タグ操作のレート制限 (req/分) */
    private static final int TAG_RATE_PER_MINUTE = 20;

    /** バケット保持期間（非アクセス時）。レート窓（1分）より十分長く、OOM は防ぐ。 */
    private static final Duration BUCKET_TTL = Duration.ofMinutes(10);

    /** キャッシュ最大エントリ数。LRU で古いものから淘汰する。 */
    private static final long MAX_BUCKETS = 10_000L;

    /** 添付ファイル操作を判定するパターン */
    private static final Pattern ATTACHMENT_PATTERN =
            Pattern.compile("^/api/v1/quick-memos/[^/]+/attachments/(presign|confirm)$");

    /** タグ操作を判定するパターン */
    private static final Pattern TAG_PATTERN =
            Pattern.compile("^/api/v1/(me/tags|teams/[^/]+/tags|organizations/[^/]+/tags).*$");

    /** CRUD操作を判定するパターン */
    private static final Pattern CRUD_PATTERN =
            Pattern.compile("^/api/v1/quick-memos(/.*)?$");

    /** 添付ファイル操作用バケットキャッシュ */
    private final Cache<String, Bucket> attachmentBuckets;

    /** タグ操作用バケットキャッシュ */
    private final Cache<String, Bucket> tagBuckets;

    /** CRUD用バケットキャッシュ */
    private final Cache<String, Bucket> crudBuckets;

    public QuickMemoRateLimitFilter() {
        this.attachmentBuckets = Caffeine.<String, Bucket>newBuilder()
                .expireAfterAccess(BUCKET_TTL)
                .maximumSize(MAX_BUCKETS)
                .build();
        this.tagBuckets = Caffeine.<String, Bucket>newBuilder()
                .expireAfterAccess(BUCKET_TTL)
                .maximumSize(MAX_BUCKETS)
                .build();
        this.crudBuckets = Caffeine.<String, Bucket>newBuilder()
                .expireAfterAccess(BUCKET_TTL)
                .maximumSize(MAX_BUCKETS)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getServletPath();

        // GETは除外（読み取り専用はレート制限なし）
        if ("GET".equalsIgnoreCase(method)) {
            return true;
        }

        // 対象エンドポイントでなければスキップ
        return !ATTACHMENT_PATTERN.matcher(path).matches()
                && !TAG_PATTERN.matcher(path).matches()
                && !CRUD_PATTERN.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();
        String userKey = resolveUserKey(request);

        Bucket bucket;
        if (ATTACHMENT_PATTERN.matcher(path).matches()) {
            bucket = attachmentBuckets.get(userKey, k -> newBucket(ATTACHMENT_RATE_PER_MINUTE));
        } else if (TAG_PATTERN.matcher(path).matches()) {
            bucket = tagBuckets.get(userKey, k -> newBucket(TAG_RATE_PER_MINUTE));
        } else {
            bucket = crudBuckets.get(userKey, k -> newBucket(CRUD_RATE_PER_MINUTE));
        }

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
        }
    }

    /**
     * 認証済みなら userId を、未認証なら IP をキーにする。
     */
    private String resolveUserKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            return "u:" + auth.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private Bucket newBucket(int capacityPerMinute) {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(capacityPerMinute, Duration.ofMinutes(1)))
                .build();
    }
}
