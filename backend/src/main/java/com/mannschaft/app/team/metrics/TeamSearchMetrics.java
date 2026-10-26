package com.mannschaft.app.team.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * F15.4 組織内チーム（店舗）検索 — Micrometer メトリクス一元管理。
 *
 * <p>設計書 {@code docs/features/F15.4_team_store_search_within_org.md} の Phase 3 計測要件に準拠し、
 * 以下 3 種類のメトリクスを {@code /actuator/prometheus} 経由で公開する。</p>
 *
 * <ul>
 *   <li>{@code team.search.requests{visibility_scope, authenticated}} — 検索実行回数</li>
 *   <li>{@code team.search.latency} — 検索処理レイテンシ</li>
 *   <li>{@code team.search.result.empty} — 検索結果 0 件の回数</li>
 * </ul>
 *
 * <p>tag 設計:
 * <ul>
 *   <li>{@code visibility_scope}: {@code PUBLIC_ONLY}（未ログイン or 非組織メンバー） /
 *       {@code MEMBER}（組織メンバー）</li>
 *   <li>{@code authenticated}: {@code true} / {@code false}</li>
 * </ul>
 * </p>
 *
 * <p>本クラスは成功時の検索のみを記録する。{@code OrganizationNotFoundException} など
 * 途中で抜けるケースは計上しない（呼び出し側で記録対象を区別する）。</p>
 */
@Component
@RequiredArgsConstructor
public class TeamSearchMetrics {

    /** 検索結果が組織メンバー視点（PUBLIC + 非公開系すべて）であることを示す tag 値。 */
    public static final String SCOPE_MEMBER = "MEMBER";

    /** 検索結果が公開チーム視点（PUBLIC のみ）であることを示す tag 値。 */
    public static final String SCOPE_PUBLIC_ONLY = "PUBLIC_ONLY";

    private static final String METRIC_REQUESTS = "team.search.requests";
    private static final String METRIC_LATENCY = "team.search.latency";
    private static final String METRIC_RESULT_EMPTY = "team.search.result.empty";

    private static final String TAG_VISIBILITY_SCOPE = "visibility_scope";
    private static final String TAG_AUTHENTICATED = "authenticated";

    private final MeterRegistry meterRegistry;

    private Counter requestsPublicAnonymousCounter;
    private Counter requestsPublicAuthenticatedCounter;
    private Counter requestsMemberAuthenticatedCounter;
    private Timer latencyTimer;
    private Counter emptyResultCounter;

    @PostConstruct
    void init() {
        // visibility_scope × authenticated の組み合わせは現実的に 3 通り（MEMBER は必ず authenticated=true）。
        // PUBLIC_ONLY × authenticated=true は「ログイン済みだが組織非メンバー」のケース。
        this.requestsPublicAnonymousCounter = Counter.builder(METRIC_REQUESTS)
                .tag(TAG_VISIBILITY_SCOPE, SCOPE_PUBLIC_ONLY)
                .tag(TAG_AUTHENTICATED, "false")
                .description("組織内チーム検索の実行回数（PUBLIC のみ可視・未ログイン）")
                .register(meterRegistry);
        this.requestsPublicAuthenticatedCounter = Counter.builder(METRIC_REQUESTS)
                .tag(TAG_VISIBILITY_SCOPE, SCOPE_PUBLIC_ONLY)
                .tag(TAG_AUTHENTICATED, "true")
                .description("組織内チーム検索の実行回数（PUBLIC のみ可視・ログイン済みだが非組織メンバー）")
                .register(meterRegistry);
        this.requestsMemberAuthenticatedCounter = Counter.builder(METRIC_REQUESTS)
                .tag(TAG_VISIBILITY_SCOPE, SCOPE_MEMBER)
                .tag(TAG_AUTHENTICATED, "true")
                .description("組織内チーム検索の実行回数（組織メンバー・PUBLIC + 非公開系すべて可視）")
                .register(meterRegistry);

        this.latencyTimer = Timer.builder(METRIC_LATENCY)
                .description("組織内チーム検索の処理レイテンシ")
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.emptyResultCounter = Counter.builder(METRIC_RESULT_EMPTY)
                .description("組織内チーム検索が 0 件を返した回数")
                .register(meterRegistry);
    }

    /**
     * 検索開始時に Timer.Sample を取得する。
     *
     * @return 計測開始時の {@link Timer.Sample}
     */
    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * 検索成功時に呼び出してメトリクスを記録する。
     *
     * @param visibilityScope {@link #SCOPE_PUBLIC_ONLY} / {@link #SCOPE_MEMBER}
     * @param authenticated   ログイン済み true / 未ログイン false
     * @param sample          {@link #startTimer()} で取得した Sample（{@code null} 時はレイテンシ記録をスキップ）
     * @param resultCount     当該ページの結果件数（0 ならば empty counter を increment）
     */
    public void recordSearch(String visibilityScope, boolean authenticated, Timer.Sample sample, int resultCount) {
        // requests カウンター
        if (SCOPE_MEMBER.equals(visibilityScope)) {
            // MEMBER スコープは必ず authenticated=true（未ログインでは到達不能）
            requestsMemberAuthenticatedCounter.increment();
        } else if (authenticated) {
            requestsPublicAuthenticatedCounter.increment();
        } else {
            requestsPublicAnonymousCounter.increment();
        }

        // latency
        if (sample != null) {
            sample.stop(latencyTimer);
        }

        // empty result
        if (resultCount == 0) {
            emptyResultCounter.increment();
        }
    }
}
