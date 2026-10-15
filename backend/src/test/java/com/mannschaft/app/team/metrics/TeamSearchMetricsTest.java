package com.mannschaft.app.team.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link TeamSearchMetrics} の単体テスト。
 *
 * <p>設計書 {@code docs/features/F15.4_team_store_search_within_org.md} Phase 3 計測要件に準拠し、
 * {@link SimpleMeterRegistry} を用いて以下を検証する。</p>
 *
 * <ul>
 *   <li>{@code team.search.requests}{visibility_scope, authenticated} Counter の分岐</li>
 *   <li>{@code team.search.latency} Timer の記録</li>
 *   <li>{@code team.search.result.empty} Counter の 0 件分岐</li>
 *   <li>{@code sample == null} 時の透過動作</li>
 * </ul>
 *
 * <p>本クラスは {@code @PostConstruct void init()} を Spring に依存せず呼び出すため、
 * {@link ReflectionTestUtils#invokeMethod} で明示的に初期化する。</p>
 */
@DisplayName("TeamSearchMetrics — F15.4 検索計測メトリクスの記録動作")
class TeamSearchMetricsTest {

    private MeterRegistry registry;
    private TeamSearchMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new TeamSearchMetrics(registry);
        // @PostConstruct init() は Spring コンテキストなしでは呼ばれないので、明示的に起動する
        ReflectionTestUtils.invokeMethod(metrics, "init");
    }

    // -------------------------------------------------------------------
    // 1) team.search.requests — visibility_scope × authenticated 分岐
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("team.search.requests Counter — タグ分岐")
    class RequestsCounter {

        @Test
        @DisplayName("MEMBER スコープ × authenticated=true で MEMBER カウンタがインクリメントされる")
        void records_memberScopeAuthenticated() {
            metrics.recordSearch(TeamSearchMetrics.SCOPE_MEMBER, true, null, 5);

            Counter member = registry.find("team.search.requests")
                    .tag("visibility_scope", TeamSearchMetrics.SCOPE_MEMBER)
                    .tag("authenticated", "true")
                    .counter();
            assertThat(member).isNotNull();
            assertThat(member.count()).isEqualTo(1.0);

            // PUBLIC_ONLY 系には流れないこと
            Counter publicAnon = registry.find("team.search.requests")
                    .tag("visibility_scope", TeamSearchMetrics.SCOPE_PUBLIC_ONLY)
                    .tag("authenticated", "false")
                    .counter();
            Counter publicAuth = registry.find("team.search.requests")
                    .tag("visibility_scope", TeamSearchMetrics.SCOPE_PUBLIC_ONLY)
                    .tag("authenticated", "true")
                    .counter();
            assertThat(publicAnon.count()).isEqualTo(0.0);
            assertThat(publicAuth.count()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("PUBLIC_ONLY × authenticated=false で 匿名カウンタがインクリメントされる")
        void records_publicOnlyAnonymous() {
            metrics.recordSearch(TeamSearchMetrics.SCOPE_PUBLIC_ONLY, false, null, 3);

            Counter publicAnon = registry.find("team.search.requests")
                    .tag("visibility_scope", TeamSearchMetrics.SCOPE_PUBLIC_ONLY)
                    .tag("authenticated", "false")
                    .counter();
            assertThat(publicAnon).isNotNull();
            assertThat(publicAnon.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("PUBLIC_ONLY × authenticated=true（非組織メンバー）で 認証済み公開カウンタがインクリメントされる")
        void records_publicOnlyAuthenticated() {
            metrics.recordSearch(TeamSearchMetrics.SCOPE_PUBLIC_ONLY, true, null, 1);

            Counter publicAuth = registry.find("team.search.requests")
                    .tag("visibility_scope", TeamSearchMetrics.SCOPE_PUBLIC_ONLY)
                    .tag("authenticated", "true")
                    .counter();
            assertThat(publicAuth).isNotNull();
            assertThat(publicAuth.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("複数回呼び出すと正しく累計される")
        void records_accumulates() {
            metrics.recordSearch(TeamSearchMetrics.SCOPE_MEMBER, true, null, 1);
            metrics.recordSearch(TeamSearchMetrics.SCOPE_MEMBER, true, null, 2);
            metrics.recordSearch(TeamSearchMetrics.SCOPE_PUBLIC_ONLY, false, null, 3);

            Counter member = registry.find("team.search.requests")
                    .tag("visibility_scope", TeamSearchMetrics.SCOPE_MEMBER)
                    .tag("authenticated", "true")
                    .counter();
            Counter publicAnon = registry.find("team.search.requests")
                    .tag("visibility_scope", TeamSearchMetrics.SCOPE_PUBLIC_ONLY)
                    .tag("authenticated", "false")
                    .counter();
            assertThat(member.count()).isEqualTo(2.0);
            assertThat(publicAnon.count()).isEqualTo(1.0);
        }
    }

    // -------------------------------------------------------------------
    // 2) team.search.latency — Timer 記録
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("team.search.latency Timer — レイテンシ計測")
    class LatencyTimer {

        @Test
        @DisplayName("startTimer → recordSearch で latency Timer が 1 回記録される")
        void records_latencyOnce() {
            Timer.Sample sample = metrics.startTimer();
            assertThat(sample).isNotNull();
            metrics.recordSearch(TeamSearchMetrics.SCOPE_MEMBER, true, sample, 1);

            Timer timer = registry.find("team.search.latency").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("sample が null でも例外を投げずに透過する（カウンタのみ記録）")
        void doesNotThrow_whenSampleIsNull() {
            assertThatCode(() ->
                    metrics.recordSearch(TeamSearchMetrics.SCOPE_MEMBER, true, null, 1)
            ).doesNotThrowAnyException();

            // latency Timer は登録自体は init() で行われているが、stop していないので count=0
            Timer timer = registry.find("team.search.latency").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(0L);

            // requests カウンタは記録されている
            Counter member = registry.find("team.search.requests")
                    .tag("visibility_scope", TeamSearchMetrics.SCOPE_MEMBER)
                    .tag("authenticated", "true")
                    .counter();
            assertThat(member.count()).isEqualTo(1.0);
        }
    }

    // -------------------------------------------------------------------
    // 3) team.search.result.empty — 0 件結果カウンタ
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("team.search.result.empty Counter — 0 件結果の検出")
    class EmptyResultCounter {

        @Test
        @DisplayName("resultCount=0 のとき empty カウンタがインクリメントされる")
        void increments_whenZeroResult() {
            metrics.recordSearch(TeamSearchMetrics.SCOPE_MEMBER, true, null, 0);

            Counter empty = registry.find("team.search.result.empty").counter();
            assertThat(empty).isNotNull();
            assertThat(empty.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("resultCount>0 のときは empty カウンタはインクリメントされない")
        void doesNotIncrement_whenNonZeroResult() {
            metrics.recordSearch(TeamSearchMetrics.SCOPE_MEMBER, true, null, 1);
            metrics.recordSearch(TeamSearchMetrics.SCOPE_PUBLIC_ONLY, false, null, 20);

            Counter empty = registry.find("team.search.result.empty").counter();
            assertThat(empty).isNotNull();
            assertThat(empty.count()).isEqualTo(0.0);
        }
    }

    // -------------------------------------------------------------------
    // 4) Smoke — 全 5 系列の Meter が初期化時に登録される
    // -------------------------------------------------------------------

    @Test
    @DisplayName("Smoke: init() 完了時点で requests×3 / latency / empty の Meter が全て登録されている")
    void smoke_allMetersRegisteredAfterInit() {
        // requests × 3 系列
        assertThat(registry.find("team.search.requests")
                .tag("visibility_scope", TeamSearchMetrics.SCOPE_PUBLIC_ONLY)
                .tag("authenticated", "false")
                .counter()).isNotNull();
        assertThat(registry.find("team.search.requests")
                .tag("visibility_scope", TeamSearchMetrics.SCOPE_PUBLIC_ONLY)
                .tag("authenticated", "true")
                .counter()).isNotNull();
        assertThat(registry.find("team.search.requests")
                .tag("visibility_scope", TeamSearchMetrics.SCOPE_MEMBER)
                .tag("authenticated", "true")
                .counter()).isNotNull();

        // latency Timer
        assertThat(registry.find("team.search.latency").timer()).isNotNull();

        // empty Counter
        assertThat(registry.find("team.search.result.empty").counter()).isNotNull();
    }
}
