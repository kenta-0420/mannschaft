package com.mannschaft.app.quickmemo;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * ポイっとメモ機能のユーザー別レートリミットフィルタ。
 *
 * <p>以下のエンドポイントに対してユーザー単位のレートリミットを適用する:</p>
 * <ul>
 *   <li>CRUD 操作 ({@code /api/v1/quick-memos/**}): 60 req/分</li>
 *   <li>添付ファイル操作 ({@code /api/v1/quick-memos/{id}/attachments/presign}, {@code /confirm}): 10 req/分</li>
 *   <li>タグ操作 ({@code /api/v1/me/tags}, {@code /api/v1/teams/{id}/tags}, {@code /api/v1/organizations/{id}/tags}): 20 req/分</li>
 * </ul>
 *
 * <p><b>Valkey 化（第二陣A）</b>: 旧実装の Bucket4j + Caffeine（プロセス内カウント）は
 * ECS 複数タスク構成でタスク数に比例して実効上限が緩むため、
 * {@link ValkeyRateLimiter}（docs/security/06 §4.3）に移行した。
 * 旧 Bucket4j greedy refill 由来のフレーク（{@code QuickMemoControllerTest}）は
 * Valkey 固定ウィンドウ方式への移行で根治される。</p>
 */
@Component
public class QuickMemoRateLimitFilter extends AbstractRateLimitFilter {

    /** CRUD操作のレート制限 (req/分) */
    private static final int CRUD_RATE_PER_MINUTE = 60;

    /** 添付ファイル操作のレート制限 (req/分) */
    private static final int ATTACHMENT_RATE_PER_MINUTE = 10;

    /** タグ操作のレート制限 (req/分) */
    private static final int TAG_RATE_PER_MINUTE = 20;

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** 添付ファイル操作を判定するパターン */
    private static final Pattern ATTACHMENT_PATTERN =
            Pattern.compile("^/api/v1/quick-memos/[^/]+/attachments/(presign|confirm)$");

    /** タグ操作を判定するパターン */
    private static final Pattern TAG_PATTERN =
            Pattern.compile("^/api/v1/(me/tags|teams/[^/]+/tags|organizations/[^/]+/tags).*$");

    /** CRUD操作を判定するパターン */
    private static final Pattern CRUD_PATTERN =
            Pattern.compile("^/api/v1/quick-memos(/.*)?$");

    /** Valkey zone 接頭辞。 */
    private static final String ZONE_PREFIX = "quickmemo:";

    public QuickMemoRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
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
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        String path = request.getServletPath();

        // 評価順序: より具体的なものから判定する（ATTACHMENT は CRUD より先に）
        if (ATTACHMENT_PATTERN.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "ATTACHMENT", ATTACHMENT_RATE_PER_MINUTE, WINDOW);
        }
        if (TAG_PATTERN.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "TAG", TAG_RATE_PER_MINUTE, WINDOW);
        }
        if (CRUD_PATTERN.matcher(path).matches()) {
            return new RateLimitRule(ZONE_PREFIX + "CRUD", CRUD_RATE_PER_MINUTE, WINDOW);
        }
        return null;
    }
}
