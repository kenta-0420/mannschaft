package com.mannschaft.app.schedule.visibility;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.schedule.ScheduleVisibility;

/**
 * F00 Phase B — {@link ScheduleVisibilityResolver} で扱う効果的な可視性レベル。
 *
 * <p>機能側の {@link ScheduleVisibility}（3 値）は TEAM / ORGANIZATION スコープを前提としており、
 * PERSONAL スコープのスケジュールには「作成者本人のみ可視」というセマンティクスが必要となる。
 * 既存 enum を改修せず（設計書 §15 D-3）、Resolver 内部だけで PERSONAL 判定を表現するため、
 * 本 enum は {@link ScheduleVisibility} の 3 値に加えて
 * {@link #PERSONAL_PRIVATE} を持つ「Resolver 内部用の派生 enum」として定義する。</p>
 *
 * <p>{@link ScheduleVisibilityProjection#visibility()} が PERSONAL スコープ行で
 * {@link #PERSONAL_PRIVATE} を返し、{@link ScheduleVisibilityResolver#toStandard} 経由で
 * {@link StandardVisibility#PRIVATE} に正規化される。</p>
 */
public enum ScheduleEffectiveVisibility {
    /** {@link ScheduleVisibility#MEMBERS_ONLY}. */
    MEMBERS_ONLY,
    /** {@link ScheduleVisibility#ORGANIZATION}. */
    ORGANIZATION,
    /** {@link ScheduleVisibility#CUSTOM_TEMPLATE}. */
    CUSTOM_TEMPLATE,
    /**
     * Resolver 内部派生値: PERSONAL スコープ（作成者本人のみ可視）。
     *
     * <p>機能側 {@link ScheduleVisibility} には PRIVATE 値が無いため、Resolver パイプラインで
     * 「作成者本人のみ可視」を表現するための擬似値。{@link StandardVisibility#PRIVATE} に正規化される。</p>
     */
    PERSONAL_PRIVATE;

    /**
     * 機能側 {@link ScheduleVisibility} を本 enum に写像する。
     *
     * @param v 機能側 enum 値
     * @return 対応する本 enum 値
     */
    public static ScheduleEffectiveVisibility from(ScheduleVisibility v) {
        return switch (v) {
            case MEMBERS_ONLY -> MEMBERS_ONLY;
            case ORGANIZATION -> ORGANIZATION;
            case CUSTOM_TEMPLATE -> CUSTOM_TEMPLATE;
        };
    }
}
