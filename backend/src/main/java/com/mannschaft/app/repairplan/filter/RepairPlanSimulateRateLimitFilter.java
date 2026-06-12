package com.mannschaft.app.repairplan.filter;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * 修繕計画シミュレーション用レートリミットフィルタ（F08.8 Phase 2）。
 *
 * <p>{@code POST /api/v1/<scopeType>/<scopeId>/repair-plan/scenarios/simulate}
 * に対して 2 段階のレートリミットを課す:</p>
 * <ul>
 *   <li>ユーザー単位: 20 req/分</li>
 *   <li>スコープ単位（scope_type + scope_id）: 100 req/分</li>
 * </ul>
 *
 * <p>シミュレーション計算は重い処理のため、連続リクエストによるサーバー過負荷を防ぐ。</p>
 *
 * <p><b>二重制限の設計意図</b>: 基底 {@link AbstractRateLimitFilter#doFilterInternal} は
 * 単一の {@link RateLimitRule} しか処理できない。本フィルタはユーザー単位とスコープ単位の
 * 2 種類の制限を持つため、{@link #doFilterInternal} を最小限オーバーライドし、
 * {@link ValkeyRateLimiter#tryConsume} を user/scope の 2 zone で 2 回呼ぶ実装にする。
 * 両方が allowed の場合のみリクエストを通過させる。どちらかが超過した場合は 429 を返す。
 * ヘッダー付与・429 書き出しは基底の {@code applyRateLimitHeaders} / {@code writeTooManyRequests}
 * を再利用することで §4.3 標準仕様に準拠する。</p>
 *
 * <p><b>Valkey 化（第二陣B）</b>: 旧実装の Bucket4j + Caffeine（プロセス内カウント）は
 * ECS 複数タスク構成でタスク数に比例して実効上限が緩むため、
 * {@link ValkeyRateLimiter}（docs/security/06 §4.3）に移行した。</p>
 */
@Component
public class RepairPlanSimulateRateLimitFilter extends AbstractRateLimitFilter {

    /** ユーザー単位の分あたり許容リクエスト数（旧実装から不変）*/
    private static final int USER_CAPACITY_PER_MINUTE = 20;

    /** スコープ単位の分あたり許容リクエスト数（旧実装から不変）*/
    private static final int SCOPE_CAPACITY_PER_MINUTE = 100;

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** マッチ対象のパス末尾 */
    private static final String SIMULATE_PATH_SUFFIX = "/repair-plan/scenarios/simulate";

    /** ユーザー単位の Valkey zone */
    private static final String ZONE_USER = "repairplan:simulate:user";

    /** スコープ単位の Valkey zone */
    private static final String ZONE_SCOPE = "repairplan:simulate:scope";

    private final ObjectProvider<ValkeyRateLimiter> rateLimiterProvider;

    public RepairPlanSimulateRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
        // 二重制限のため自クラスでも保持する（doFilterInternal オーバーライドで直接使用）
        this.rateLimiterProvider = rateLimiterProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getServletPath();
        if (path == null) return true;
        return !path.endsWith(SIMULATE_PATH_SUFFIX);
    }

    /**
     * 基底の単一ルール処理では二重制限を表現できないため doFilterInternal をオーバーライドする。
     * ユーザーキーとスコープキーの両方に対して {@link ValkeyRateLimiter#tryConsume} を呼び、
     * 両方 allowed の場合のみ通過させる。どちらかが超過したら 429 を返す。
     *
     * <p>ヘッダー付与・429 書き出しは基底メソッドを再利用して §4.3 準拠を保つ。</p>
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        ValkeyRateLimiter rateLimiter = rateLimiterProvider.getIfAvailable();
        if (rateLimiter == null) {
            // Bean 不在時（@WebMvcTest 等の最小コンテキスト）は素通し
            chain.doFilter(request, response);
            return;
        }

        String userKey = resolveClientKey(request);
        String scopeKey = resolveScopeKey(request);

        RateLimitResult userResult = rateLimiter.tryConsume(
                ZONE_USER, userKey, USER_CAPACITY_PER_MINUTE, WINDOW);
        RateLimitResult scopeResult = rateLimiter.tryConsume(
                ZONE_SCOPE, scopeKey, SCOPE_CAPACITY_PER_MINUTE, WINDOW);

        if (userResult.allowed() && scopeResult.allowed()) {
            // 両方の制限を通過 — ユーザー制限のヘッダーを付与（より厳しい側）
            applyRateLimitHeaders(response, userResult);
            chain.doFilter(request, response);
        } else {
            // どちらかが超過 — 超過したほうのヘッダーを返す
            RateLimitResult exceeded = !userResult.allowed() ? userResult : scopeResult;
            applyRateLimitHeaders(response, exceeded);
            writeTooManyRequests(response, exceeded);
        }
    }

    /**
     * 基底の resolveRule は使用しない（doFilterInternal を直接オーバーライドするため）。
     * shouldNotFilter を通過する場合に呼ばれることはないが、抽象メソッドのため null を返す。
     */
    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        return null;
    }

    /**
     * スコープキーを URL パスから抽出する。
     * URL 形式: /api/v1/{scopeType}/{scopeId}/repair-plan/scenarios/simulate
     */
    private String resolveScopeKey(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null) return "scope:unknown";
        // /api/v1/ 以降の最初の 2 セグメントを取得
        String[] parts = path.split("/");
        // parts[0]="" parts[1]="api" parts[2]="v1" parts[3]=scopeType parts[4]=scopeId
        if (parts.length >= 5) {
            return "scope:" + parts[3] + ":" + parts[4];
        }
        return "scope:unknown";
    }
}
