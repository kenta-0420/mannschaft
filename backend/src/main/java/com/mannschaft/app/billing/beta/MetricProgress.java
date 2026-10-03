package com.mannschaft.app.billing.beta;

/**
 * F20.3 ベータ特典: 付与条件の 1 指標あたりの進捗（設計書 02 §2）。
 *
 * <p>{@code actual}（実測値）と {@code required}（閾値）を持つ内部モデル。付与判定は
 * 「非 NULL の指標だけを AND 評価・境界は以上（{@code actual >= required}）」で行う
 * （{@link BetaPerkEligibilityService}）。API 層（隊2）が {@code MetricProgress} DTO へ
 * マップして本人・シスアドへ進捗を開示する（ADHD フレンドリーな「あと N 日」表示・04 §1）。</p>
 *
 * @param metricKey 指標キー（{@code activeDays} / {@code membershipTenureDays} / {@code activeMembers}）
 * @param actual    実測値
 * @param required  閾値（この値<b>以上</b>で達成）
 */
public record MetricProgress(String metricKey, long actual, long required) {

    /** この指標を単独で満たすか（{@code actual >= required}）。 */
    public boolean met() {
        return actual >= required;
    }
}
