package com.mannschaft.app.billing.beta;

import java.util.List;

/**
 * F20.3 ベータ特典: 付与条件の評価結果（設計書 02 §2）。
 *
 * <p>{@link BetaPerkEligibilityService#evaluate} が返す内部モデル。API 層（隊2）が
 * {@code EligibilityStatus} DTO へマップし、{@link BetaGrantService} が
 * {@code beta_grants.criteria_snapshot}（JSON）へ焼き付ける原資として使う。</p>
 *
 * <p><b>キャッシュ格納の都合</b>: 本 record は {@code betaPerk:eligibility}（TTL 10 分・Valkey）に
 * 格納されるため、Entity を保持せず値のみを持つ（{@code BetaPerkCriteriaEntity} は保持しない）。
 * criteria_snapshot に必要な閾値は {@link #metrics}（各指標の {@code required}）と
 * {@link #evaluationWindowDays} から復元する。</p>
 *
 * @param eligible             全ての定義済み指標を満たすか（非 NULL 指標の AND・境界は「以上」）
 * @param metrics              指標ごとの進捗（定義済み＝非 NULL の指標のみ）
 * @param betaPhase            評価対象のベータ段階
 * @param evaluationWindowDays activeDays の評価ウィンドウ（日・snapshot 用）
 */
public record EligibilityResult(
        boolean eligible,
        List<MetricProgress> metrics,
        int betaPhase,
        int evaluationWindowDays) {
}
