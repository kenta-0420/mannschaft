package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.schedule.ScheduleKeepScopeType;

/**
 * キープ（日付未定の予定）の実効可視性レベルを {@link StandardVisibility} で表現する（F03.17 §4.6.2）。
 *
 * <p><strong>キープは可視性設定列を持たない</strong>（設定項目を増やさないのが F03.17 §1.3 の要件）。
 * したがって機能側 visibility enum が存在せず、写像の入力はスコープ種別のみである。</p>
 *
 * <ul>
 *   <li>{@link ScheduleKeepScopeType#TEAM} / {@link ScheduleKeepScopeType#ORGANIZATION}
 *       → {@link StandardVisibility#MEMBERS_AND_ABOVE}</li>
 *   <li>{@link ScheduleKeepScopeType#PERSONAL} → {@link StandardVisibility#PRIVATE}</li>
 * </ul>
 *
 * <p><strong>なぜ {@link StandardVisibility#SCOPE_AFFILIATED} ではなく
 * {@link StandardVisibility#MEMBERS_AND_ABOVE} なのか</strong>:
 * {@code SCOPE_AFFILIATED}（直接所属軸）は応援者（SUPPORTER）とゲストを含むため、
 * チーム内の相談段階の情報が応援者に見えてしまう。キープは「内輪の相談」そのものであり、
 * 応援者を除外する閾値である {@code MEMBERS_AND_ABOVE} が求めている意味そのままの値である。
 * {@code EventVisibilityMapper} が {@code MEMBERS_ONLY → MEMBERS_AND_ABOVE} へ締め直した先例と
 * 同じ判断であり、F00 の語彙の範囲内で完結する（独自の可視性述語は一切書かない）。</p>
 *
 * <p><strong>個人スコープを {@link StandardVisibility#PRIVATE} にする理由</strong>:
 * 個人スコープのキープはスコープ（TEAM / ORGANIZATION）を持たず、閾値ラダーの評価対象になり得ない。
 * F00 の語彙では「作成者本人のみ閲覧可能」＝ {@code PRIVATE} がそのまま該当する
 * （設計書 §4.6 「個人スコープは {@code user_id == getCurrentUserId()}」）。</p>
 *
 * <p>将来キープに可視性設定を持たせる場合は、本メソッドを列参照の {@code switch} に変えるだけでよい
 * （{@code EventVisibilityMapper} と同じ形）。</p>
 */
public final class ScheduleKeepVisibilityMapper {

    private ScheduleKeepVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * キープのスコープ種別から実効 {@link StandardVisibility} を返す。
     *
     * @param scopeType キープのスコープ種別（non-null）
     * @return 対応する StandardVisibility 値
     */
    public static StandardVisibility toStandard(ScheduleKeepScopeType scopeType) {
        return switch (scopeType) {
            case TEAM, ORGANIZATION -> StandardVisibility.MEMBERS_AND_ABOVE;
            case PERSONAL -> StandardVisibility.PRIVATE;
        };
    }
}
