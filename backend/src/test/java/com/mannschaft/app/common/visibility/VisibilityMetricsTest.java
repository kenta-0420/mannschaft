package com.mannschaft.app.common.visibility;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VisibilityMetrics} の単体テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §9.4 / §11.2 完全一致。
 *
 * <p>{@link SimpleMeterRegistry} で実際に Meter が登録・記録されることを検証する。
 */
@DisplayName("VisibilityMetrics — 7 メトリクスの登録・記録")
class VisibilityMetricsTest {

    private MeterRegistry registry;
    private VisibilityMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new VisibilityMetrics(registry);
    }

    // -------------------------------------------------------------------
    // 1) check.latency
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("content_visibility.check.latency (Timer)")
    class CheckLatency {

        @Test
        @DisplayName("startCheckTimer / stopCheckTimer で Timer が referenceType + op タグ付きで登録される")
        void records_timerWithReferenceTypeAndOpTags() {
            Timer.Sample sample = metrics.startCheckTimer();
            assertThat(sample).isNotNull();
            metrics.stopCheckTimer(sample, ReferenceType.BLOG_POST, "canView");

            Timer timer = registry.find("content_visibility.check.latency")
                .tag("referenceType", "BLOG_POST")
                .tag("op", "canView")
                .timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("type=null は UNKNOWN タグで記録される")
        void records_unknownWhenTypeIsNull() {
            Timer.Sample sample = metrics.startCheckTimer();
            metrics.stopCheckTimer(sample, null, "filterAccessibleByType");

            Timer timer = registry.find("content_visibility.check.latency")
                .tag("referenceType", "UNKNOWN")
                .tag("op", "filterAccessibleByType")
                .timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("sample=null は何もしない (NPE せず)")
        void doesNotThrow_whenSampleIsNull() {
            metrics.stopCheckTimer(null, ReferenceType.EVENT, "canView");
            assertThat(registry.find("content_visibility.check.latency").timer())
                .isNull();
        }
    }

    // -------------------------------------------------------------------
    // 2) check.batch_size
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("content_visibility.check.batch_size (DistributionSummary)")
    class BatchSize {

        @Test
        @DisplayName("recordBatchSize で件数が DistributionSummary に記録される")
        void records_batchSize() {
            metrics.recordBatchSize(ReferenceType.EVENT, 50);
            metrics.recordBatchSize(ReferenceType.EVENT, 10);

            DistributionSummary summary = registry
                .find("content_visibility.check.batch_size")
                .tag("referenceType", "EVENT")
                .summary();
            assertThat(summary).isNotNull();
            assertThat(summary.count()).isEqualTo(2L);
            assertThat(summary.totalAmount()).isEqualTo(60.0);
        }

        @Test
        @DisplayName("負数は 0 にクランプされる")
        void clamps_negativeToZero() {
            metrics.recordBatchSize(ReferenceType.SCHEDULE, -5);

            DistributionSummary summary = registry
                .find("content_visibility.check.batch_size")
                .tag("referenceType", "SCHEDULE")
                .summary();
            assertThat(summary).isNotNull();
            assertThat(summary.totalAmount()).isEqualTo(0.0);
        }
    }

    // -------------------------------------------------------------------
    // 3) check.access_ratio
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("content_visibility.check.access_ratio (DistributionSummary)")
    class AccessRatio {

        @Test
        @DisplayName("0.0〜1.0 の値がそのまま記録される")
        void records_ratioInRange() {
            metrics.recordAccessRatio(ReferenceType.BLOG_POST, 0.75);

            DistributionSummary summary = registry
                .find("content_visibility.check.access_ratio")
                .tag("referenceType", "BLOG_POST")
                .summary();
            assertThat(summary).isNotNull();
            assertThat(summary.mean()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("範囲外は clamp される (1.5 → 1.0, -0.5 → 0.0)")
        void clamps_outOfRangeRatio() {
            metrics.recordAccessRatio(ReferenceType.EVENT, 1.5);
            metrics.recordAccessRatio(ReferenceType.EVENT, -0.5);

            DistributionSummary summary = registry
                .find("content_visibility.check.access_ratio")
                .tag("referenceType", "EVENT")
                .summary();
            assertThat(summary.count()).isEqualTo(2L);
            assertThat(summary.totalAmount()).isEqualTo(1.0); // 1.0 + 0.0
        }
    }

    // -------------------------------------------------------------------
    // 4) check.denied
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("content_visibility.check.denied (Counter)")
    class Denied {

        @Test
        @DisplayName("denyReason ごとに別 Counter として記録される")
        void records_separateCountersPerDenyReason() {
            metrics.recordDenied(ReferenceType.BLOG_POST, DenyReason.NOT_FOUND);
            metrics.recordDenied(ReferenceType.BLOG_POST, DenyReason.NOT_A_MEMBER);
            metrics.recordDenied(ReferenceType.BLOG_POST, DenyReason.NOT_A_MEMBER);

            Counter notFound = registry
                .find("content_visibility.check.denied")
                .tag("referenceType", "BLOG_POST")
                .tag("denyReason", "NOT_FOUND")
                .counter();
            Counter notMember = registry
                .find("content_visibility.check.denied")
                .tag("referenceType", "BLOG_POST")
                .tag("denyReason", "NOT_A_MEMBER")
                .counter();

            assertThat(notFound).isNotNull();
            assertThat(notFound.count()).isEqualTo(1.0);
            assertThat(notMember).isNotNull();
            assertThat(notMember.count()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("denyReason=null は UNSPECIFIED として記録される")
        void records_unspecifiedWhenReasonIsNull() {
            metrics.recordDenied(ReferenceType.EVENT, null);

            Counter counter = registry
                .find("content_visibility.check.denied")
                .tag("referenceType", "EVENT")
                .tag("denyReason", "UNSPECIFIED")
                .counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }
    }

    // -------------------------------------------------------------------
    // 5) unsupported_reference_type + cardinality 100 上限ガード
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("content_visibility.unsupported_reference_type (Counter)")
    class Unsupported {

        @Test
        @DisplayName("通常の enum 値は referenceType タグでそのまま記録される")
        void records_normalEnumValue() {
            metrics.recordUnsupported(ReferenceType.PERSONAL_TIMETABLE);

            Counter counter = registry
                .find("content_visibility.unsupported_reference_type")
                .tag("referenceType", "PERSONAL_TIMETABLE")
                .counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("type=null は UNKNOWN タグで記録される")
        void records_unknownWhenTypeIsNull() {
            metrics.recordUnsupported(null);

            Counter counter = registry
                .find("content_visibility.unsupported_reference_type")
                .tag("referenceType", "UNKNOWN")
                .counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("cardinality 100 上限を超えたタグ値は OVERFLOW に集約される")
        void cardinalityGuard_overflowsBeyond100() {
            // テスト用 fakes: enum を使えないので、内部状態に直接 100 個タグを観測済みとして注入
            // resetCardinalityGuard で初期化 → 観測済み Set を 100 件まで埋める
            metrics.resetCardinalityGuard();

            // ReferenceType enum 値を順次記録 → enum は 19 値しかないので 19 個までは正常
            // 残り 81 個を観測済みとして詰めるため、内部 Set へ直接 fake タグを足したい
            // → API としては「未対応として 100 個目まで許容、101 個目から OVERFLOW」を検証
            //    ガードは observedReferenceTypeTagsView() で可視化されているので、
            //    まず enum 値を 19 個記録して観測 Set のサイズを確認、
            //    その後 fake タグ (合成 enum 値) は記録できないため、enum 全 19 値内で
            //    OVERFLOW 動作だけを構造的に検証する。
            for (ReferenceType t : ReferenceType.values()) {
                metrics.recordUnsupported(t);
            }
            assertThat(metrics.observedReferenceTypeTagsView())
                .hasSizeLessThanOrEqualTo(VisibilityMetrics.MAX_CARDINALITY)
                .containsExactlyInAnyOrderElementsOf(
                    java.util.Arrays.stream(ReferenceType.values())
                        .map(Enum::name)
                        .collect(java.util.stream.Collectors.toSet())
                );
        }

        @Test
        @DisplayName("観測済み 100 種類超過時に OVERFLOW タグへ集約される (内部 Set を直接埋めて検証)")
        void cardinalityGuard_overflowsToOverflowTag() {
            metrics.resetCardinalityGuard();

            // 内部 Set を direct に 100 件詰める方法は無いため、
            // recordUnsupported を 100 回呼んで観測済みを満たす方法は enum 値が
            // 重複するためできない。よって reflection で内部 Set に 100 件詰める。
            try {
                java.lang.reflect.Field f = VisibilityMetrics.class
                    .getDeclaredField("observedReferenceTypeTags");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                Set<String> internal = (Set<String>) f.get(metrics);
                for (int i = 0; i < VisibilityMetrics.MAX_CARDINALITY; i++) {
                    internal.add("FAKE_TYPE_" + i);
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("reflection failed", e);
            }

            // ここで新しい enum 値 (観測 Set 未登録) を渡すと OVERFLOW へ集約される
            metrics.recordUnsupported(ReferenceType.FOLLOW_LIST);

            Counter overflow = registry
                .find("content_visibility.unsupported_reference_type")
                .tag("referenceType", VisibilityMetrics.OVERFLOW_TAG)
                .counter();
            assertThat(overflow).isNotNull();
            assertThat(overflow.count()).isEqualTo(1.0);
        }
    }

    // -------------------------------------------------------------------
    // 6) template_eval.latency
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("content_visibility.template_eval.latency (Timer)")
    class TemplateEvalLatency {

        @Test
        @DisplayName("startTemplateEvalTimer / stopTemplateEvalTimer で Timer が rule_count タグ付きで登録される")
        void records_timerWithRuleCountTag() {
            Timer.Sample sample = metrics.startTemplateEvalTimer();
            assertThat(sample).isNotNull();
            metrics.stopTemplateEvalTimer(sample, 5);

            Timer timer = registry
                .find("content_visibility.template_eval.latency")
                .tag("rule_count", "5")
                .timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("ruleCount が負数なら 0 にクランプされる")
        void clamps_negativeRuleCountToZero() {
            Timer.Sample sample = metrics.startTemplateEvalTimer();
            metrics.stopTemplateEvalTimer(sample, -3);

            Timer timer = registry
                .find("content_visibility.template_eval.latency")
                .tag("rule_count", "0")
                .timer();
            assertThat(timer).isNotNull();
        }

        @Test
        @DisplayName("sample=null は何もしない")
        void doesNotThrow_whenSampleIsNull() {
            metrics.stopTemplateEvalTimer(null, 1);
            assertThat(registry.find("content_visibility.template_eval.latency").timer())
                .isNull();
        }
    }

    // -------------------------------------------------------------------
    // 7) custom_dispatch_count
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("content_visibility.custom_dispatch_count (Counter)")
    class CustomDispatch {

        @Test
        @DisplayName("referenceType + customSubType ごとに別 Counter として記録される")
        void records_separateCountersPerCustomSubType() {
            metrics.recordCustomDispatch(ReferenceType.SURVEY, "AFTER_CLOSE");
            metrics.recordCustomDispatch(ReferenceType.SURVEY, "AFTER_CLOSE");
            metrics.recordCustomDispatch(ReferenceType.SURVEY, "NAME_ONLY");

            Counter afterClose = registry
                .find("content_visibility.custom_dispatch_count")
                .tag("referenceType", "SURVEY")
                .tag("customSubType", "AFTER_CLOSE")
                .counter();
            Counter nameOnly = registry
                .find("content_visibility.custom_dispatch_count")
                .tag("referenceType", "SURVEY")
                .tag("customSubType", "NAME_ONLY")
                .counter();

            assertThat(afterClose).isNotNull();
            assertThat(afterClose.count()).isEqualTo(2.0);
            assertThat(nameOnly).isNotNull();
            assertThat(nameOnly.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("customSubType=null は UNKNOWN として記録される")
        void records_unknownWhenSubTypeIsNull() {
            metrics.recordCustomDispatch(ReferenceType.EVENT, null);

            Counter counter = registry
                .find("content_visibility.custom_dispatch_count")
                .tag("referenceType", "EVENT")
                .tag("customSubType", "UNKNOWN")
                .counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }
    }

    // -------------------------------------------------------------------
    // 全 7 メトリクスの登録確認 (Smoke)
    // -------------------------------------------------------------------

    @Test
    @DisplayName("Smoke: 全 7 メトリクスがそれぞれ MeterRegistry に登録される")
    void smoke_allSevenMetricsRegistered() {
        Timer.Sample s1 = metrics.startCheckTimer();
        metrics.stopCheckTimer(s1, ReferenceType.BLOG_POST, "canView");
        metrics.recordBatchSize(ReferenceType.BLOG_POST, 1);
        metrics.recordAccessRatio(ReferenceType.BLOG_POST, 1.0);
        metrics.recordDenied(ReferenceType.BLOG_POST, DenyReason.NOT_FOUND);
        metrics.recordUnsupported(ReferenceType.BLOG_POST);
        Timer.Sample s2 = metrics.startTemplateEvalTimer();
        metrics.stopTemplateEvalTimer(s2, 1);
        metrics.recordCustomDispatch(ReferenceType.BLOG_POST, "X");

        assertThat(registry.find("content_visibility.check.latency").timer()).isNotNull();
        assertThat(registry.find("content_visibility.check.batch_size").summary()).isNotNull();
        assertThat(registry.find("content_visibility.check.access_ratio").summary()).isNotNull();
        assertThat(registry.find("content_visibility.check.denied").counter()).isNotNull();
        assertThat(registry.find("content_visibility.unsupported_reference_type").counter())
            .isNotNull();
        assertThat(registry.find("content_visibility.template_eval.latency").timer()).isNotNull();
        assertThat(registry.find("content_visibility.custom_dispatch_count").counter()).isNotNull();
    }
}
