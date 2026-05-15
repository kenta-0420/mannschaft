package com.mannschaft.app.pointcard.filter;

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
 * F18 ポイントカードウォレットのユーザー別レートリミットフィルタ。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §9.5
 *
 * <p>本フィルタが扱う対象:
 * <ul>
 *   <li>{@code GET    /api/v1/point-cards/providers}      ─ 60 req/分（2A）</li>
 *   <li>{@code PUT    /api/v1/point-cards/settings}       ─ 10 req/時（2A）</li>
 *   <li>{@code POST   /api/v1/point-cards}                ─ 30 req/時（2B 追加）</li>
 *   <li>{@code GET    /api/v1/point-cards/{id}}           ─ 120 req/分（2B 追加）</li>
 *   <li>{@code POST   /api/v1/point-cards/{id}/used}      ─ 600 req/時（2B 追加）</li>
 *   <li>{@code GET    /api/v1/point-cards/groups}         ─ 60 req/分（S3 追加）</li>
 *   <li>{@code POST   /api/v1/point-cards/groups}         ─ 30 req/時（S3 追加）</li>
 *   <li>{@code POST   /api/v1/point-cards/groups/{id}/presentation-start} ─ 600 req/時（S3 追加）</li>
 * </ul>
 *
 * <p>パターンは「具体度の高いものから順に」評価する。たとえば
 * {@code /point-cards/{id}/used} を {@code /point-cards/{id}} より先に判定すること。
 *
 * <p>キャッシュ戦略: Caffeine の expireAfterAccess=2 時間 + maximumSize=10000。
 */
@Component
public class PointCardRateLimitFilter extends OncePerRequestFilter {

    // ──── レート定義 ─────────────────────────────
    private static final int PROVIDERS_RATE_PER_MINUTE = 60;
    private static final int SETTINGS_PUT_RATE_PER_HOUR = 10;
    private static final int CREATE_CARD_RATE_PER_HOUR = 30;
    private static final int GET_DETAIL_RATE_PER_MINUTE = 120;
    private static final int RECORD_USED_RATE_PER_HOUR = 600;
    // S3 グループ機能
    private static final int LIST_GROUPS_RATE_PER_MINUTE = 60;
    private static final int CREATE_GROUP_RATE_PER_HOUR = 30;
    private static final int PRESENTATION_RATE_PER_HOUR = 600;

    private static final Duration BUCKET_TTL = Duration.ofHours(2);
    private static final long MAX_BUCKETS = 10_000L;

    // ──── パスパターン ───────────────────────────
    private static final Pattern PROVIDERS_PATH =
            Pattern.compile("^/api/v1/point-cards/providers$");

    private static final Pattern SETTINGS_PATH =
            Pattern.compile("^/api/v1/point-cards/settings$");

    /** カード一覧 (GET /) と 作成 (POST /) の共通パス。 */
    private static final Pattern CARDS_ROOT_PATH =
            Pattern.compile("^/api/v1/point-cards$");

    /** カード利用記録: GET 詳細パターンより先にマッチ判定する。 */
    private static final Pattern CARD_USED_PATH =
            Pattern.compile("^/api/v1/point-cards/[0-9a-fA-F-]{36}/used$");

    /** カード詳細 / 更新 / 削除の {@code /{id}} パターン。 */
    private static final Pattern CARD_ID_PATH =
            Pattern.compile("^/api/v1/point-cards/[0-9a-fA-F-]{36}$");

    /** グループ一覧 (GET) / 作成 (POST) の共通パス。 */
    private static final Pattern GROUPS_ROOT_PATH =
            Pattern.compile("^/api/v1/point-cards/groups$");

    /** グループ提示モード起動パターン。具体度が高いので /{id} より先に判定する。 */
    private static final Pattern GROUP_PRESENTATION_PATH =
            Pattern.compile("^/api/v1/point-cards/groups/[0-9a-fA-F-]{36}/presentation-start$");

    // ──── バケット ──────────────────────────────
    private final Cache<String, Bucket> providersBuckets;
    private final Cache<String, Bucket> settingsBuckets;
    private final Cache<String, Bucket> createCardBuckets;
    private final Cache<String, Bucket> getDetailBuckets;
    private final Cache<String, Bucket> recordUsedBuckets;
    private final Cache<String, Bucket> listGroupsBuckets;
    private final Cache<String, Bucket> createGroupBuckets;
    private final Cache<String, Bucket> presentationBuckets;

    public PointCardRateLimitFilter() {
        this.providersBuckets = newCache();
        this.settingsBuckets = newCache();
        this.createCardBuckets = newCache();
        this.getDetailBuckets = newCache();
        this.recordUsedBuckets = newCache();
        this.listGroupsBuckets = newCache();
        this.createGroupBuckets = newCache();
        this.presentationBuckets = newCache();
    }

    private static Cache<String, Bucket> newCache() {
        return Caffeine.<String, Bucket>newBuilder()
                .expireAfterAccess(BUCKET_TTL)
                .maximumSize(MAX_BUCKETS)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 本フィルタの管理対象パスのみ通す（その他のリクエストは初期段階で除外）
        String method = request.getMethod();
        String path = request.getServletPath();

        if ("GET".equalsIgnoreCase(method) && PROVIDERS_PATH.matcher(path).matches()) {
            return false;
        }
        if ("PUT".equalsIgnoreCase(method) && SETTINGS_PATH.matcher(path).matches()) {
            return false;
        }
        if ("POST".equalsIgnoreCase(method) && CARDS_ROOT_PATH.matcher(path).matches()) {
            return false;
        }
        if ("GET".equalsIgnoreCase(method) && CARD_ID_PATH.matcher(path).matches()) {
            return false;
        }
        if ("POST".equalsIgnoreCase(method) && CARD_USED_PATH.matcher(path).matches()) {
            return false;
        }
        // S3 グループ機能（GROUPS_ROOT_PATH は CARD_ID_PATH より具体度が高いので問題ない）
        if (("GET".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method))
                && GROUPS_ROOT_PATH.matcher(path).matches()) {
            return false;
        }
        if ("POST".equalsIgnoreCase(method) && GROUP_PRESENTATION_PATH.matcher(path).matches()) {
            return false;
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String method = request.getMethod();
        String path = request.getServletPath();
        String userKey = resolveUserKey(request);

        Bucket bucket;
        String retryAfter;

        // 評価順序: より具体的なものから判定する
        if ("POST".equalsIgnoreCase(method) && GROUP_PRESENTATION_PATH.matcher(path).matches()) {
            bucket = presentationBuckets.get(userKey, k -> newBucketPerHour(PRESENTATION_RATE_PER_HOUR));
            retryAfter = "3600";
        } else if ("POST".equalsIgnoreCase(method) && GROUPS_ROOT_PATH.matcher(path).matches()) {
            bucket = createGroupBuckets.get(userKey, k -> newBucketPerHour(CREATE_GROUP_RATE_PER_HOUR));
            retryAfter = "3600";
        } else if ("GET".equalsIgnoreCase(method) && GROUPS_ROOT_PATH.matcher(path).matches()) {
            bucket = listGroupsBuckets.get(userKey, k -> newBucketPerMinute(LIST_GROUPS_RATE_PER_MINUTE));
            retryAfter = "60";
        } else if ("POST".equalsIgnoreCase(method) && CARD_USED_PATH.matcher(path).matches()) {
            bucket = recordUsedBuckets.get(userKey, k -> newBucketPerHour(RECORD_USED_RATE_PER_HOUR));
            retryAfter = "3600";
        } else if ("GET".equalsIgnoreCase(method) && CARD_ID_PATH.matcher(path).matches()) {
            bucket = getDetailBuckets.get(userKey, k -> newBucketPerMinute(GET_DETAIL_RATE_PER_MINUTE));
            retryAfter = "60";
        } else if ("POST".equalsIgnoreCase(method) && CARDS_ROOT_PATH.matcher(path).matches()) {
            bucket = createCardBuckets.get(userKey, k -> newBucketPerHour(CREATE_CARD_RATE_PER_HOUR));
            retryAfter = "3600";
        } else if ("GET".equalsIgnoreCase(method) && PROVIDERS_PATH.matcher(path).matches()) {
            bucket = providersBuckets.get(userKey, k -> newBucketPerMinute(PROVIDERS_RATE_PER_MINUTE));
            retryAfter = "60";
        } else if ("PUT".equalsIgnoreCase(method) && SETTINGS_PATH.matcher(path).matches()) {
            bucket = settingsBuckets.get(userKey, k -> newBucketPerHour(SETTINGS_PUT_RATE_PER_HOUR));
            retryAfter = "3600";
        } else {
            chain.doFilter(request, response);
            return;
        }

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", retryAfter);
        }
    }

    private String resolveUserKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            return "u:" + auth.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private Bucket newBucketPerMinute(int capacity) {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(capacity, Duration.ofMinutes(1)))
                .build();
    }

    private Bucket newBucketPerHour(int capacity) {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(capacity, Duration.ofHours(1)))
                .build();
    }
}
