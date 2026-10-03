package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.calendar.CalendarScopeAutoColor;

import java.util.List;

/**
 * レイヤー自動色の既存公開窓口（F03.19 §3.3）。
 *
 * <p>予定と TODO の導出実装を共通側に集約し、公開済みのパレット・キー・色の契約を維持する。</p>
 */
public final class CalendarLayerAutoColor {

    private CalendarLayerAutoColor() {
    }

    /** 既存の固定12色パレット。 */
    public static final List<String> PALETTE = CalendarScopeAutoColor.PALETTE;

    /** 既存のスコープキーを返す。 */
    public static String scopeKey(String scopeType, Long scopeId) {
        return CalendarScopeAutoColor.scopeKey(scopeType, scopeId);
    }

    /** 既存のスコープ自動色を返す。 */
    public static String resolve(String scopeType, Long scopeId) {
        return CalendarScopeAutoColor.resolve(scopeType, scopeId);
    }
}
