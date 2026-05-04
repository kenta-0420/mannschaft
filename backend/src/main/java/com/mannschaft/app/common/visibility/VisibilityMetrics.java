package com.mannschaft.app.common.visibility;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * F00 共通可視性判定基盤の Micrometer メトリクス集約。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §9.4 完全一致。
 *
 * <p>提供する 7 メトリクスは設計書の表と一対一に対応する:
 * <ul>
 *   <li>{@code content_visibility.check.latency} — Timer (referenceType, op タグ)</li>
 *   <li>{@code content_visibility.check.batch_size} — DistributionSummary (referenceType)</li>
 *   <li>{@code content_visibility.check.access_ratio} — DistributionSummary (referenceType)</li>
 *   <li>{@code content_visibility.check.denied} — Counter (referenceType, denyReason)</li>
 *   <li>{@code content_visibility.unsupported_reference_type} — Counter (referenceType, max 100 + OVERFLOW)</li>
 *   <li>{@code content_visibility.template_eval.latency} — Timer (rule_count)</li>
 *   <li>{@code content_visibility.custom_dispatch_count} — Counter (referenceType, customSubType)</li>
 * </ul>
 *
 * <p><strong>cardinality 制御 (§11.2)</strong>:
 * {@code recordUnsupported} の {@code referenceType} タグは現状 enum なので最大 19 種だが、
 * 将来 DB 由来の不明 type を tag 化する余地に備え、100 種類を上限とし
 * 超過時は {@code referenceType=OVERFLOW} に集約する。
 *
 * <p><strong>Grafana ダッシュボード</strong>:
 * 設置先 {@code infra/grafana/dashboards/backend_visibility.json}
 * (UID: {@code f00-content-visibility-resolver})。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VisibilityMetrics {

    /** §11.2 cardinality 爆発防止のための referenceType タグ上限。 */
    static final int MAX_CARDINALITY = 100;

    /** cardinality 上限超過時に集約するタグ値。 */
    static final String OVERFLOW_TAG = "OVERFLOW";

    /** {@link Timer} のタグキー: 対象の reference_type. */
    private static final String TAG_REFERENCE_TYPE = "referenceType";
    /** {@link Timer} のタグキー: 操作種別 (canView / filterAccessible 等). */
    private static final String TAG_OP = "op";
    /** {@link Counter} のタグキー: deny の理由. */
    private static final String TAG_DENY_REASON = "denyReason";
    /** {@link Timer} のタグキー: テンプレート評価のルール数. */
    private static final String TAG_RULE_COUNT = "rule_count";
    /** {@link Counter} のタグキー: CUSTOM 値経由の細分種別. */
    private static final String TAG_CUSTOM_SUB_TYPE = "customSubType";

    private static final String METRIC_CHECK_LATENCY = "content_visibility.check.latency";
    private static final String METRIC_CHECK_BATCH_SIZE = "content_visibility.check.batch_size";
    private static final String METRIC_CHECK_ACCESS_RATIO = "content_visibility.check.access_ratio";
    private static final String METRIC_CHECK_DENIED = "content_visibility.check.denied";
    private static final String METRIC_UNSUPPORTED = "content_visibility.unsupported_reference_type";
    private static final String METRIC_TEMPLATE_EVAL_LATENCY = "content_visibility.template_eval.latency";
    private static final String METRIC_CUSTOM_DISPATCH = "content_visibility.custom_dispatch_count";

    private final MeterRegistry meterRegistry;

    /**
     * cardinality ガード用に「これまで観測した referenceType タグ値」を追跡する。
     * 100 種類を超えたら以降は {@link #OVERFLOW_TAG} に集約する。
     */
    private final Set<String> observedReferenceTypeTags = ConcurrentHashMap.newKeySet();

    // -------------------------------------------------------------------
    // 1) 単発・バッチ判定のレイテンシ
    // -------------------------------------------------------------------

    /**
     * {@code canView} / {@code filterAccessible} 等の判定開始時に呼ぶ。
     *
     * @return Timer.Sample (停止時に {@link #stopCheckTimer} へ渡す)
     */
    public Timer.Sample startCheckTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * {@link #startCheckTimer} で取得した Sample を停止し、レイテンシを記録する。
     *
     * @param sample 開始時に取得した {@link Timer.Sample}
     * @param type   対象の reference_type ({@code null} 可、null は "UNKNOWN" として記録)
     * @param op     操作種別 (canView / filterAccessible / filterAccessibleByType /
     *               decide / assertCanView)
     */
    public void stopCheckTimer(Timer.Sample sample, ReferenceType type, String op) {
        if (sample == null) {
            return;
        }
        Timer timer = Timer.builder(METRIC_CHECK_LATENCY)
                .description("ContentVisibilityChecker 判定のレイテンシ")
                .tag(TAG_REFERENCE_TYPE, refTypeTag(type))
                .tag(TAG_OP, op == null ? "unknown" : op)
                .publishPercentileHistogram()
                .register(meterRegistry);
        sample.stop(timer);
    }

    // -------------------------------------------------------------------
    // 2) バッチ判定の入力件数
    // -------------------------------------------------------------------

    /**
     * バッチ判定 ({@code filterAccessible}) の入力 ID 件数を記録する。
     *
     * @param type 対象の reference_type
     * @param size 入力件数 (0 も記録、負数は 0 にクランプ)
     */
    public void recordBatchSize(ReferenceType type, int size) {
        DistributionSummary.builder(METRIC_CHECK_BATCH_SIZE)
                .description("filterAccessible バッチ判定の入力件数")
                .tag(TAG_REFERENCE_TYPE, refTypeTag(type))
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Math.max(0, size));
    }

    // -------------------------------------------------------------------
    // 3) バッチ判定の許可率
    // -------------------------------------------------------------------

    /**
     * バッチ判定の許可率 (許可数 / 入力数) を記録する。
     *
     * @param type  対象の reference_type
     * @param ratio 0.0〜1.0 の値 (範囲外は clamp)
     */
    public void recordAccessRatio(ReferenceType type, double ratio) {
        double clamped = Math.max(0.0, Math.min(1.0, ratio));
        DistributionSummary.builder(METRIC_CHECK_ACCESS_RATIO)
                .description("filterAccessible バッチ判定の許可率 (0.0〜1.0)")
                .tag(TAG_REFERENCE_TYPE, refTypeTag(type))
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(clamped);
    }

    // -------------------------------------------------------------------
    // 4) deny の発生数
    // -------------------------------------------------------------------

    /**
     * {@code decide} が deny を返した回数を記録する。
     *
     * @param type   対象の reference_type
     * @param reason 拒否理由
     */
    public void recordDenied(ReferenceType type, DenyReason reason) {
        Counter.builder(METRIC_CHECK_DENIED)
                .description("decide が deny を返した回数")
                .tag(TAG_REFERENCE_TYPE, refTypeTag(type))
                .tag(TAG_DENY_REASON, reason == null ? "UNSPECIFIED" : reason.name())
                .register(meterRegistry)
                .increment();
    }

    // -------------------------------------------------------------------
    // 5) 未対応 type の検出回数 (cardinality 100 上限ガード)
    // -------------------------------------------------------------------

    /**
     * 未対応 {@link ReferenceType} の検出を記録する (§11.2 fail-closed)。
     *
     * <p>cardinality 爆発を防ぐため、観測済みタグ値が {@link #MAX_CARDINALITY} に達した後に
     * 新しい値が来た場合は {@link #OVERFLOW_TAG} に集約する。enum の値そのものは
     * 上限を下回るため通常は機能しないが、将来 DB 由来の文字列を tag 化する場合の防御策。
     *
     * @param type 未対応として検出された reference_type
     */
    public void recordUnsupported(ReferenceType type) {
        String tagValue = refTypeTag(type);
        String effectiveTag;
        if (observedReferenceTypeTags.contains(tagValue)) {
            effectiveTag = tagValue;
        } else if (observedReferenceTypeTags.size() < MAX_CARDINALITY) {
            observedReferenceTypeTags.add(tagValue);
            effectiveTag = tagValue;
        } else {
            effectiveTag = OVERFLOW_TAG;
        }
        Counter.builder(METRIC_UNSUPPORTED)
                .description("未対応 ReferenceType の検出回数 (cardinality 上限 " + MAX_CARDINALITY + ")")
                .tag(TAG_REFERENCE_TYPE, effectiveTag)
                .register(meterRegistry)
                .increment();
    }

    // -------------------------------------------------------------------
    // 6) テンプレート評価のレイテンシ
    // -------------------------------------------------------------------

    /**
     * {@code VisibilityTemplateEvaluator} 等のテンプレート評価開始時に呼ぶ。
     *
     * @return Timer.Sample
     */
    public Timer.Sample startTemplateEvalTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * テンプレート評価 Timer を停止しレイテンシを記録する。
     *
     * @param sample    開始時に取得した {@link Timer.Sample}
     * @param ruleCount 評価したルールの個数 (0 以上、負数は 0 にクランプ)
     */
    public void stopTemplateEvalTimer(Timer.Sample sample, int ruleCount) {
        if (sample == null) {
            return;
        }
        int safeCount = Math.max(0, ruleCount);
        Timer timer = Timer.builder(METRIC_TEMPLATE_EVAL_LATENCY)
                .description("VisibilityTemplate 評価のレイテンシ")
                .tag(TAG_RULE_COUNT, Integer.toString(safeCount))
                .publishPercentileHistogram()
                .register(meterRegistry);
        sample.stop(timer);
    }

    // -------------------------------------------------------------------
    // 7) CUSTOM 値経由の判定回数 (§5.1.4 偏り検出)
    // -------------------------------------------------------------------

    /**
     * {@link StandardVisibility#CUSTOM} 経由の判定を記録する。
     *
     * <p>本メトリクスは「CUSTOM 値が実際にはどの細分種別に解決されたか」を追跡し、
     * Resolver の偏り (§5.1.4) を可視化するために用いる。
     *
     * @param type          対象の reference_type
     * @param customSubType 細分種別 (例: NAME_ONLY / AFTER_CLOSE 等、{@code null} 可)
     */
    public void recordCustomDispatch(ReferenceType type, String customSubType) {
        Counter.builder(METRIC_CUSTOM_DISPATCH)
                .description("StandardVisibility.CUSTOM 経由の判定回数")
                .tag(TAG_REFERENCE_TYPE, refTypeTag(type))
                .tag(TAG_CUSTOM_SUB_TYPE, customSubType == null ? "UNKNOWN" : customSubType)
                .register(meterRegistry)
                .increment();
    }

    // -------------------------------------------------------------------
    // 補助
    // -------------------------------------------------------------------

    /** {@link ReferenceType} を tag 文字列に変換する ({@code null} 安全). */
    private static String refTypeTag(ReferenceType type) {
        return type == null ? "UNKNOWN" : type.name();
    }

    /**
     * テスト用: 観測済み referenceType タグ集合を返す (不変ビュー)。
     * cardinality ガードの動作確認に用いる。
     *
     * @return 観測済みタグ値の {@link Set} (不変)
     */
    Set<String> observedReferenceTypeTagsView() {
        return Set.copyOf(observedReferenceTypeTags);
    }

    /**
     * テスト用: cardinality ガード状態をクリアする。
     * 単体テスト間で観測状態を分離するために使用する。
     */
    void resetCardinalityGuard() {
        observedReferenceTypeTags.clear();
    }

    /**
     * 内部可視: タグ参照の存在確認用 (将来の最適化に備えて Tags 経由で再利用したい場合のヘルパ)。
     * 現状は未使用だが API 表面の互換性として保持する。
     */
    @SuppressWarnings("unused")
    private static Tags tagsOf(Tag... tags) {
        return Tags.of(tags);
    }

    /** ヘルパ: ナノ秒を ms に変換するデバッグ用 (現状未使用、将来の log 連携に備えて保持). */
    @SuppressWarnings("unused")
    private static double toMillis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }
}
